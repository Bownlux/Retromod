/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Checks a large mod jar for zip-bomb shapes from its headers, then counts what each entry unpacks to. */
public final class ArchiveCheck {

    /** Entries one archive may hold, the same bound every transform loop applies. */
    public static final int MAX_ENTRIES = 100_000;

    // Deflate tops out near 1032:1. Measured on a 32,000-entry mod: one file reached 40:1, the
    // jar as a whole 2.2:1, and nothing over 1 MiB passed 6:1. A bomb needs ratios near the
    // deflate limit, so these leave several times the real headroom and still stop one.
    /** Highest unpacked-to-stored ratio for the whole archive. */
    public static final long MAX_OVERALL_RATIO = 20;
    /** Highest ratio for one entry of at least {@link #RATIO_CHECKED_FROM_BYTES}. */
    public static final long MAX_ENTRY_RATIO = 100;
    /** Smaller entries are not ratio-checked: a 1 MB file of zeros is harmless at any ratio. */
    public static final long RATIO_CHECKED_FROM_BYTES = 4L << 20;

    /** Nested archives checked below the outer jar, as deep as any transform opens them. */
    public static final int MAX_NESTED_DEPTH = 4;
    /** A nested archive up to this size is checked in memory, a larger one from a temporary file. */
    static final long NESTED_IN_MEMORY_BYTES = 16L << 20;
    /** The outer central directory is read into memory once, so it is bounded. */
    static final long MAX_CENTRAL_DIRECTORY_BYTES = 64L << 20;
    /** A nested archive's central directory; nested jars up to 128 MiB never need more. */
    static final long MAX_NESTED_CENTRAL_DIRECTORY_BYTES = 8L << 20;

    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int END_SIGNATURE = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int ZIP64_END_SIGNATURE = 0x06064b50;
    private static final int END_SIZE = 22;
    private static final long ZIP64_MAGIC = 0xFFFFFFFFL;

    /**
     * What a check found.
     *
     * @param problem        null when the archive passed, otherwise what failed, in words
     * @param entries        entries in the central directory
     * @param unpackedBytes  the unpacked size the central directory declares
     * @param storedBytes    the compressed size the central directory declares
     * @param contentCounted true when every entry was unpacked and counted, not only its headers read
     */
    public record Result(String problem, int entries, long unpackedBytes, long storedBytes,
            boolean contentCounted) {
        public boolean passed() {
            return problem == null;
        }
    }

    /** The sizes the central directory declares. */
    public record Totals(int entries, long unpackedBytes, long storedBytes) {}

    /**
     * The limits one check shares across the jar and every archive nested in it.
     *
     * @param nestedInflateBytes   what nested archives may unpack to together, on top of the jar's own size
     * @param maxEntries           entries in the jar and its nested archives together
     * @param nestedArchiveBytes   the largest nested archive the check opens
     * @param nestedDirectoryBytes the largest central directory of a nested archive
     * @param classBytes           what one archive's class files may unpack to together
     */
    record Limits(long nestedInflateBytes, int maxEntries, long nestedArchiveBytes,
            long nestedDirectoryBytes, long classBytes) {
        // The nested limits match what the transforms allow a jar's nested jars.
        static final Limits DEFAULT = new Limits(ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, MAX_ENTRIES,
                ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, MAX_NESTED_CENTRAL_DIRECTORY_BYTES,
                ZipSecurity.MAX_CLASS_TOTAL_SIZE);
    }

    /** Thrown inside the check, turned into a failed {@link Result}. */
    private static final class Problem extends Exception {
        // True when the bytes hold no zip at all, which a nested file may legitimately be.
        final boolean notAnArchive;

        Problem(String message) {
            this(message, false);
        }

        Problem(String message, boolean notAnArchive) {
            super(message, null, false, false);
            this.notAnArchive = notAnArchive;
        }
    }

    /** What one check may still unpack and count, shared by every nesting level. */
    private static final class Budget {
        final Limits limits;
        private long inflateLeft;
        private int entriesLeft;

        Budget(long inflateBytes, Limits limits) {
            this.limits = limits;
            this.inflateLeft = inflateBytes;
            this.entriesLeft = limits.maxEntries();
        }

        void entries(int count) throws Problem {
            if (count > entriesLeft) {
                throw new Problem("it and the archives inside it hold more than " + limits.maxEntries()
                        + " entries together");
            }
            entriesLeft -= count;
        }

        void inflate(long bytes, String name) throws Problem {
            if (bytes > inflateLeft) {
                throw new Problem("the archives inside it unpack to more than "
                        + humanBytes(limits.nestedInflateBytes()) + " together, reached at "
                        + printable(name));
            }
            inflateLeft -= bytes;
        }
    }

    private ArchiveCheck() {}

    /** Reads only the central directory and sums the sizes it declares. Throws for a damaged one. */
    public static Totals readTotals(Path archive) throws IOException {
        try (Source source = Source.of(archive)) {
            Directory directory = readDirectory(source, MAX_CENTRAL_DIRECTORY_BYTES);
            return directory.totals();
        } catch (Problem problem) {
            throw new IOException(problem.getMessage());
        }
    }

    /** The header check: central directory and every local header, never the entry data. */
    public static Result checkStructure(Path archive) throws IOException {
        return check(archive, false, Limits.DEFAULT);
    }

    /** The header check, then every entry unpacked once and counted, and nested archives checked. */
    public static Result checkContent(Path archive) throws IOException {
        return check(archive, true, Limits.DEFAULT);
    }

    /** {@link #checkContent} under other limits, so tests reach each one with small files. */
    static Result checkContent(Path archive, Limits limits) throws IOException {
        return check(archive, true, limits);
    }

    private static Result check(Path archive, boolean countContent, Limits limits) throws IOException {
        Result result;
        try (Source source = Source.of(archive)) {
            result = check(source, countContent, limits);
        }
        if (!result.passed()) return result;
        // Every transform reads the jar through java.util.zip, so that reader has to list the same entries.
        String differs = differsFromJavaZip(archive, new Totals(result.entries(), result.unpackedBytes(),
                result.storedBytes()));
        return differs == null ? result
                : new Result(differs, result.entries(), result.unpackedBytes(), result.storedBytes(), false);
    }

    private static Result check(Source source, boolean countContent, Limits limits) throws IOException {
        Directory directory;
        try {
            directory = readDirectory(source, MAX_CENTRAL_DIRECTORY_BYTES);
        } catch (Problem problem) {
            return new Result(problem.getMessage(), 0, 0, 0, false);
        }
        Totals totals = directory.totals();
        // The jar's own entries take exactly what they declare; nested archives share what is left.
        Budget budget = new Budget(saturatedAdd(totals.unpackedBytes(), limits.nestedInflateBytes()), limits);
        try {
            inspect(source, directory, 0, countContent, budget);
        } catch (Problem problem) {
            return new Result(problem.getMessage(), totals.entries(), totals.unpackedBytes(),
                    totals.storedBytes(), false);
        }
        return new Result(null, totals.entries(), totals.unpackedBytes(), totals.storedBytes(),
                countContent);
    }

    private static void inspect(Source source, Directory directory, int depth, boolean countContent,
            Budget budget) throws IOException, Problem {
        budget.entries(directory.entries().size());
        checkEntries(directory, budget.limits);
        List<Span> spans = checkLocalHeaders(source, directory);
        // File order, so the overlap check compares neighbors and the content reads run front to back.
        spans.sort(Comparator.comparingLong(Span::start));
        checkOverlaps(spans);
        if (countContent) countContent(source, spans, depth, budget);
    }

    /** The entry count and sizes java.util.zip lists differ from {@code totals}: what differs, or null. */
    static String differsFromJavaZip(Path archive, Totals totals) {
        int entries = 0;
        long unpacked = 0;
        long stored = 0;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            for (var all = zip.entries(); all.hasMoreElements(); ) {
                java.util.zip.ZipEntry entry = all.nextElement();
                entries++;
                unpacked = saturatedAdd(unpacked, Math.max(0, entry.getSize()));
                stored = saturatedAdd(stored, Math.max(0, entry.getCompressedSize()));
            }
        } catch (IOException | RuntimeException unreadable) {
            return "Java's zip reader cannot open it: " + unreadable.getMessage();
        }
        if (entries == totals.entries() && unpacked == totals.unpackedBytes()
                && stored == totals.storedBytes()) {
            return null;
        }
        return "Java's zip reader lists " + entries + " entries of " + unpacked + " bytes, where its headers list "
                + totals.entries() + " entries of " + totals.unpackedBytes() + " bytes";
    }

    // ---- Central directory ----

    /** One central directory record, with offsets already moved past any data before the archive. */
    private record Entry(String name, byte[] rawName, int flags, int method, long crc,
            long storedBytes, long unpackedBytes, long headerOffset) {
        boolean directory() {
            return name.endsWith("/");
        }
    }

    private record Directory(List<Entry> entries, long start) {
        Totals totals() {
            long unpacked = 0;
            long stored = 0;
            for (Entry entry : entries) {
                unpacked = saturatedAdd(unpacked, entry.unpackedBytes());
                stored = saturatedAdd(stored, entry.storedBytes());
            }
            return new Totals(entries.size(), unpacked, stored);
        }
    }

    /** An end record as java.util.zip takes it: where the directory ends, and its count, size, and offset. */
    private record End(long position, long entries, long directorySize, long directoryOffset) {}

    private static Directory readDirectory(Source source, long maxDirectoryBytes)
            throws IOException, Problem {
        long size = source.size();
        if (size < END_SIZE) throw new Problem("the file is too short to be a jar", true);
        // The same window and order java.util.zip searches, so both pick the same end record.
        long lowest = lowestEndPosition(size);
        byte[] tail = source.readFully(lowest, (int) (size - lowest));
        for (int i = tail.length - END_SIZE; i >= 0; i--) {
            if (int32(tail, i) != END_SIGNATURE) continue;
            End end = endRecord(source, size, lowest + i, tail, i);
            if (end != null) return directoryFrom(source, end, maxDirectoryBytes);
        }
        throw new Problem("it has no readable central directory", true);
    }

    /** The lowest position java.util.zip's backward search for an end record reaches, in 128-byte blocks. */
    private static long lowestEndPosition(long size) {
        int block = 128;
        long minHeader = Math.max(size - (END_SIZE + 0xFFFF), 0);
        long minPosition = minHeader - (block - END_SIZE);
        long lowest = size - block;
        for (long position = size - block; position >= minPosition; position -= block - END_SIZE) {
            lowest = position;
        }
        return Math.max(lowest, 0);
    }

    /** The end record at {@code position} as java.util.zip reads it, or null when it keeps searching. */
    private static End endRecord(Source source, long size, long position, byte[] tail, int at)
            throws IOException {
        long total = uint16(tail, at + 10);
        long directorySize = uint32(tail, at + 12);
        long directoryOffset = uint32(tail, at + 16);
        int commentLength = uint16(tail, at + 20);
        // A comment that stops short of the end needs a central and a local header where the record points.
        if (position + END_SIZE + commentLength != size) {
            long directoryStart = position - directorySize;
            long firstLocal = directoryStart - directoryOffset;
            if (directoryStart < 0 || firstLocal < 0
                    || !signatureAt(source, size, directoryStart, CENTRAL_SIGNATURE)
                    || !signatureAt(source, size, firstLocal, LOCAL_SIGNATURE)) {
                return null;
            }
        }
        End end = new End(position, total, directorySize, directoryOffset);
        // A ZIP64 record named by a locator right before it replaces the values when the two agree.
        if (position < 20) return end;
        byte[] locator = source.readFully(position - 20, 20);
        if (int32(locator, 0) != ZIP64_LOCATOR_SIGNATURE) return end;
        long recordPosition = int64(locator, 8);
        if (recordPosition < 0 || recordPosition > size - 56) return end;
        byte[] record = source.readFully(recordPosition, 56);
        if (int32(record, 0) != ZIP64_END_SIGNATURE) return end;
        long total64 = int64(record, 32);
        long directorySize64 = int64(record, 40);
        long directoryOffset64 = int64(record, 48);
        if ((directorySize64 != directorySize && directorySize != ZIP64_MAGIC)
                || (directoryOffset64 != directoryOffset && directoryOffset != ZIP64_MAGIC)
                || (total64 != total && total != 0xFFFF)) {
            return end;
        }
        return new End(recordPosition, total64, directorySize64, directoryOffset64);
    }

    private static boolean signatureAt(Source source, long size, long position, int signature)
            throws IOException {
        return position <= size - 4 && int32(source.readFully(position, 4), 0) == signature;
    }

    /** The central directory an end record names, checked the way java.util.zip checks it, then parsed strictly. */
    private static Directory directoryFrom(Source source, End end, long maxDirectoryBytes)
            throws IOException, Problem {
        // An end record at the very start is an empty archive.
        if (end.position() == 0) return new Directory(List.of(), 0);
        if (end.directorySize() < 0 || end.directorySize() > end.position()) {
            throw new Problem("its end record names a central directory larger than the file before it");
        }
        long directoryStart = end.position() - end.directorySize();
        long prefix = directoryStart - end.directoryOffset();
        if (end.directoryOffset() < 0 || prefix < 0) {
            throw new Problem("its end record points before the start of the file");
        }
        if (end.entries() < 0 || end.entries() > MAX_ENTRIES) {
            throw new Problem("it lists " + end.entries() + " entries; Retromod reads at most " + MAX_ENTRIES);
        }
        if (end.directorySize() > maxDirectoryBytes) {
            throw new Problem("its central directory is " + humanBytes(end.directorySize())
                    + "; Retromod reads at most " + humanBytes(maxDirectoryBytes));
        }
        byte[] central = source.readFully(directoryStart, (int) end.directorySize());
        List<Entry> entries = parseCentral(central, prefix);
        if (entries.size() != end.entries()) {
            throw new Problem("its end record lists " + end.entries() + " entries but its central directory holds "
                    + entries.size());
        }
        return new Directory(entries, directoryStart);
    }

    private static List<Entry> parseCentral(byte[] central, long prefix) throws Problem {
        List<Entry> entries = new ArrayList<>();
        int position = 0;
        while (position < central.length) {
            if (position + 46 > central.length || int32(central, position) != CENTRAL_SIGNATURE) {
                throw new Problem("its central directory is damaged at byte " + position);
            }
            if (entries.size() >= MAX_ENTRIES) {
                throw new Problem("it holds more than " + MAX_ENTRIES + " entries");
            }
            int flags = uint16(central, position + 8);
            int method = uint16(central, position + 10);
            long crc = uint32(central, position + 16);
            long stored = uint32(central, position + 20);
            long unpacked = uint32(central, position + 24);
            int nameLength = uint16(central, position + 28);
            int extraLength = uint16(central, position + 30);
            int commentLength = uint16(central, position + 32);
            int disk = uint16(central, position + 34);
            long offset = uint32(central, position + 42);
            int nameStart = position + 46;
            int extraStart = nameStart + nameLength;
            int next = extraStart + extraLength + commentLength;
            if (next > central.length) {
                throw new Problem("its central directory is damaged at byte " + position);
            }
            byte[] rawName = Arrays.copyOfRange(central, nameStart, extraStart);
            // A ZIP64 extra field holds, in this order, each value the fixed field marks as too large.
            long[] wide = zip64Values(central, extraStart, extraLength,
                    unpacked == ZIP64_MAGIC, stored == ZIP64_MAGIC, offset == ZIP64_MAGIC);
            if (wide != null) {
                unpacked = wide[0];
                stored = wide[1];
                offset = wide[2];
            }
            if (disk != 0 && disk != 0xFFFF) {
                throw new Problem("it spans several files");
            }
            if (unpacked < 0 || stored < 0 || offset < 0) {
                throw new Problem("an entry declares a negative size or offset");
            }
            String name = new String(rawName, StandardCharsets.UTF_8);
            entries.add(new Entry(name, rawName, flags, method, crc, stored, unpacked,
                    saturatedAdd(offset, prefix)));
            position = next;
        }
        return entries;
    }

    /** The unpacked size, stored size, and offset after applying a ZIP64 extra field, or null when none applies. */
    private static long[] zip64Values(byte[] data, int start, int length, boolean wideUnpacked,
            boolean wideStored, boolean wideOffset) throws Problem {
        if (!wideUnpacked && !wideStored && !wideOffset) return null;
        int position = start;
        int end = start + length;
        while (position + 4 <= end) {
            int id = uint16(data, position);
            int size = uint16(data, position + 2);
            int body = position + 4;
            if (body + size > end) break;
            if (id == 0x0001) {
                long[] values = {ZIP64_MAGIC, ZIP64_MAGIC, ZIP64_MAGIC};
                int field = body;
                if (wideUnpacked) { values[0] = need(data, field, body + size); field += 8; }
                if (wideStored) { values[1] = need(data, field, body + size); field += 8; }
                if (wideOffset) { values[2] = need(data, field, body + size); }
                return values;
            }
            position = body + size;
        }
        throw new Problem("an entry marks a size as ZIP64 but carries no ZIP64 field");
    }

    private static long need(byte[] data, int position, int end) throws Problem {
        if (position + 8 > end) throw new Problem("an entry has a short ZIP64 field");
        return int64(data, position);
    }

    // ---- Header checks ----

    private static void checkEntries(Directory directory, Limits limits) throws Problem {
        Set<String> names = new HashSet<>();
        long unpacked = 0;
        long stored = 0;
        long classes = 0;
        for (Entry entry : directory.entries()) {
            String name = entry.name();
            String canonical;
            try {
                canonical = ZipSecurity.canonicalEntryName(name);
            } catch (IOException unsafe) {
                throw new Problem("entry " + printable(name) + " has an unsafe name: " + unsafe.getMessage());
            }
            // A repeated folder carries no content; a repeated file lets two readers see different bytes.
            if (!names.add(canonical) && !entry.directory()) {
                throw new Problem("entry " + printable(name) + " appears twice");
            }
            if ((entry.flags() & 1) != 0) {
                throw new Problem("entry " + printable(name) + " is encrypted");
            }
            if (entry.method() != 0 && entry.method() != 8) {
                throw new Problem("entry " + printable(name) + " uses compression method " + entry.method()
                        + ", which Java cannot read");
            }
            if (entry.method() == 0 && entry.storedBytes() != entry.unpackedBytes()) {
                throw new Problem("entry " + printable(name) + " is stored uncompressed but declares "
                        + entry.unpackedBytes() + " bytes in " + entry.storedBytes());
            }
            if (name.endsWith(".class")) {
                if (entry.unpackedBytes() > ZipSecurity.MAX_CLASS_ENTRY_SIZE) {
                    throw new Problem("class " + printable(name) + " is " + humanBytes(entry.unpackedBytes())
                            + "; class files are never larger than " + humanBytes(ZipSecurity.MAX_CLASS_ENTRY_SIZE));
                }
                classes = saturatedAdd(classes, entry.unpackedBytes());
            }
            if (entry.unpackedBytes() >= RATIO_CHECKED_FROM_BYTES
                    && exceedsRatio(entry.unpackedBytes(), entry.storedBytes(), MAX_ENTRY_RATIO)) {
                throw new Problem("entry " + printable(name) + " unpacks to " + humanBytes(entry.unpackedBytes())
                        + " from " + humanBytes(entry.storedBytes()) + ", more than " + MAX_ENTRY_RATIO
                        + " times its stored size");
            }
            unpacked = saturatedAdd(unpacked, entry.unpackedBytes());
            stored = saturatedAdd(stored, entry.storedBytes());
        }
        // Every transform holds a jar's classes in memory at once.
        if (classes > limits.classBytes()) {
            throw new Problem("its class files unpack to " + humanBytes(classes) + "; Retromod translates at most "
                    + humanBytes(limits.classBytes()) + " of classes in one jar");
        }
        if (unpacked >= RATIO_CHECKED_FROM_BYTES && exceedsRatio(unpacked, stored, MAX_OVERALL_RATIO)) {
            throw new Problem("it unpacks to " + humanBytes(unpacked) + " from " + humanBytes(stored)
                    + ", more than " + MAX_OVERALL_RATIO + " times its stored size");
        }
    }

    private static boolean exceedsRatio(long unpacked, long stored, long ratio) {
        if (stored <= 0) return unpacked > 0;
        return unpacked / ratio > stored || (unpacked / ratio == stored && unpacked % ratio != 0);
    }

    /** Where an entry's header starts and its data ends, for the overlap check. */
    private record Span(Entry entry, long start, long dataStart, long end) {}

    private static List<Span> checkLocalHeaders(Source source, Directory directory)
            throws IOException, Problem {
        List<Span> spans = new ArrayList<>(directory.entries().size());
        for (Entry entry : directory.entries()) {
            String name = printable(entry.name());
            long headerEnd = saturatedAdd(entry.headerOffset(), 30L + entry.rawName().length);
            if (headerEnd > directory.start()) {
                throw new Problem("entry " + name + " points past the end of the entry data");
            }
            byte[] header = source.readFully(entry.headerOffset(), 30 + entry.rawName().length);
            if (int32(header, 0) != LOCAL_SIGNATURE) {
                throw new Problem("entry " + name + " points at no local header");
            }
            int flags = uint16(header, 6);
            int method = uint16(header, 8);
            long crc = uint32(header, 14);
            long stored = uint32(header, 18);
            long unpacked = uint32(header, 22);
            int nameLength = uint16(header, 26);
            int extraLength = uint16(header, 28);
            // A local name that differs from the central one lets two readers see different files.
            if (nameLength != entry.rawName().length
                    || !Arrays.equals(header, 30, 30 + nameLength, entry.rawName(), 0, nameLength)) {
                throw new Problem("entry " + name + " has a different name in its local header");
            }
            if (method != entry.method()) {
                throw new Problem("entry " + name + " has a different compression method in its local header");
            }
            // With a data descriptor (flag 8) the local sizes are written after the data instead.
            if ((flags & 8) == 0 && (entry.flags() & 8) == 0) {
                if (stored == ZIP64_MAGIC || unpacked == ZIP64_MAGIC) {
                    byte[] extra = source.readFully(entry.headerOffset() + 30 + nameLength, extraLength);
                    long[] wide = zip64Values(extra, 0, extraLength, unpacked == ZIP64_MAGIC,
                            stored == ZIP64_MAGIC, false);
                    if (wide != null) {
                        if (unpacked == ZIP64_MAGIC) unpacked = wide[0];
                        if (stored == ZIP64_MAGIC) stored = wide[1];
                    }
                }
                if (crc != entry.crc() || stored != entry.storedBytes() || unpacked != entry.unpackedBytes()) {
                    throw new Problem("entry " + name + " declares different sizes in its local header");
                }
            }
            long dataStart = entry.headerOffset() + 30 + nameLength + extraLength;
            long dataEnd = saturatedAdd(dataStart, entry.storedBytes());
            if (dataEnd > directory.start()) {
                throw new Problem("entry " + name + " runs into the central directory");
            }
            spans.add(new Span(entry, entry.headerOffset(), dataStart, dataEnd));
        }
        return spans;
    }

    /** The classic non-recursive bomb points many entries at one block of compressed data. */
    private static void checkOverlaps(List<Span> spans) throws Problem {
        for (int i = 1; i < spans.size(); i++) {
            Span before = spans.get(i - 1);
            Span after = spans.get(i);
            if (after.start() < before.end()) {
                throw new Problem("entries " + printable(before.entry().name()) + " and "
                        + printable(after.entry().name()) + " overlap in the file");
            }
        }
    }

    // ---- Content count ----

    private static void countContent(Source source, List<Span> spans, int depth, Budget budget)
            throws IOException, Problem {
        byte[] input = new byte[65536];
        byte[] output = new byte[65536];
        for (Span span : spans) {
            Entry entry = span.entry();
            if (entry.directory() && entry.unpackedBytes() == 0) continue;
            // Each entry stops once it passes what it declared, so reserving that bounds all the work.
            budget.inflate(entry.unpackedBytes(), entry.name());
            boolean nested = depth < MAX_NESTED_DEPTH && isArchiveName(entry.name())
                    && opensNested(entry, budget.limits);
            NestedCopy copy = nested ? NestedCopy.open(entry.unpackedBytes()) : null;
            try {
                long counted = entry.method() == 0
                        ? copyStored(source, span, input, copy)
                        : inflate(source, span, input, output, copy);
                if (counted != entry.unpackedBytes()) {
                    throw new Problem("entry " + printable(entry.name()) + " unpacks to " + counted
                            + " bytes, not the " + entry.unpackedBytes() + " its header declares");
                }
                if (copy != null) checkNested(copy, entry, depth, budget);
            } finally {
                if (copy != null) copy.close();
            }
        }
    }

    /**
     * Whether a nested archive is opened and checked. One past the nested limit fails where the
     * transforms translate nested jars, and elsewhere counts as plain bytes, since it is only copied.
     */
    private static boolean opensNested(Entry entry, Limits limits) throws Problem {
        if (entry.unpackedBytes() <= limits.nestedArchiveBytes()) return true;
        if (isNestedJarFolder(entry.name())) {
            throw new Problem("nested jar " + printable(entry.name()) + " is " + humanBytes(entry.unpackedBytes())
                    + "; Retromod translates nested jars up to " + humanBytes(limits.nestedArchiveBytes()));
        }
        return false;
    }

    private static boolean isNestedJarFolder(String name) {
        int slash = name.lastIndexOf('/');
        String folder = slash < 0 ? "" : name.substring(0, slash + 1);
        return folder.endsWith("META-INF/jars/") || folder.endsWith("META-INF/jarjar/");
    }

    private static long copyStored(Source source, Span span, byte[] buffer, NestedCopy copy)
            throws IOException {
        long position = span.dataStart();
        long left = span.entry().storedBytes();
        while (left > 0) {
            int chunk = (int) Math.min(buffer.length, left);
            source.readFully(position, buffer, chunk);
            if (copy != null) copy.write(buffer, chunk);
            position += chunk;
            left -= chunk;
        }
        return span.entry().storedBytes();
    }

    private static long inflate(Source source, Span span, byte[] input, byte[] output, NestedCopy copy)
            throws IOException, Problem {
        Entry entry = span.entry();
        Inflater inflater = new Inflater(true);
        try {
            long position = span.dataStart();
            long left = entry.storedBytes();
            long counted = 0;
            boolean padded = false;
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (left > 0) {
                        int chunk = (int) Math.min(input.length, left);
                        source.readFully(position, input, chunk);
                        inflater.setInput(input, 0, chunk);
                        position += chunk;
                        left -= chunk;
                    } else if (!padded) {
                        // A raw deflate stream may need one extra byte to report its end.
                        inflater.setInput(new byte[1], 0, 1);
                        padded = true;
                    } else {
                        throw new Problem("entry " + printable(entry.name()) + " ends before its data does");
                    }
                }
                int produced;
                try {
                    produced = inflater.inflate(output);
                } catch (DataFormatException damaged) {
                    throw new Problem("entry " + printable(entry.name()) + " holds damaged data");
                }
                if (produced == 0 && inflater.needsDictionary()) {
                    throw new Problem("entry " + printable(entry.name()) + " needs a preset dictionary");
                }
                counted += produced;
                // Stopping here bounds the work a lying entry can cause to what it declared.
                if (counted > entry.unpackedBytes()) {
                    throw new Problem("entry " + printable(entry.name()) + " unpacks past the "
                            + entry.unpackedBytes() + " bytes its header declares");
                }
                if (copy != null && produced > 0) copy.write(output, produced);
            }
            return counted;
        } finally {
            inflater.end();
        }
    }

    private static void checkNested(NestedCopy copy, Entry entry, int depth, Budget budget)
            throws IOException, Problem {
        try (Source nested = copy.source()) {
            Directory directory;
            try {
                directory = readDirectory(nested, budget.limits.nestedDirectoryBytes());
            } catch (Problem problem) {
                // A file that only ends in .zip or .jar is not an archive; the transforms copy it as bytes.
                if (problem.notAnArchive) return;
                throw new Problem("nested archive " + printable(entry.name()) + ": " + problem.getMessage());
            }
            try {
                inspect(nested, directory, depth + 1, true, budget);
            } catch (Problem problem) {
                throw new Problem("nested archive " + printable(entry.name()) + ": " + problem.getMessage());
            }
        }
    }

    private static boolean isArchiveName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    /** The unpacked bytes of one nested archive, in memory or in a temporary file. */
    private static final class NestedCopy implements Closeable {
        private final byte[] memory;
        private final Path file;
        private final FileChannel channel;
        private int filled;

        private NestedCopy(byte[] memory, Path file, FileChannel channel) {
            this.memory = memory;
            this.file = file;
            this.channel = channel;
        }

        static NestedCopy open(long size) throws IOException {
            if (size <= NESTED_IN_MEMORY_BYTES) return new NestedCopy(new byte[(int) size], null, null);
            Path file = com.retromod.inplace.WorkFolder.createTempFile("retromod-check-", ".zip");
            return new NestedCopy(null, file, FileChannel.open(file, StandardOpenOption.WRITE,
                    StandardOpenOption.READ));
        }

        void write(byte[] data, int length) throws IOException {
            if (memory != null) {
                // The count check runs after this, so never write past the declared size here.
                int room = Math.min(length, memory.length - filled);
                System.arraycopy(data, 0, memory, filled, room);
                filled += room;
            } else {
                ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        }

        Source source() throws IOException {
            if (memory != null) return Source.of(memory);
            // The caller still owns the channel, so the view leaves it open.
            FileChannel shared = channel;
            return new Source() {
                @Override
                public long size() throws IOException {
                    return shared.size();
                }

                @Override
                public int read(long position, byte[] target, int offset, int length) throws IOException {
                    return shared.read(ByteBuffer.wrap(target, offset, length), position);
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void close() throws IOException {
            if (channel != null) {
                try {
                    channel.close();
                } finally {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    // ---- Reading ----

    /** Positional reads from a file or a byte array. */
    private interface Source extends Closeable {
        long size() throws IOException;

        int read(long position, byte[] target, int offset, int length) throws IOException;

        default byte[] readFully(long position, int length) throws IOException {
            byte[] bytes = new byte[length];
            readFully(position, bytes, length);
            return bytes;
        }

        default void readFully(long position, byte[] target, int length) throws IOException {
            int done = 0;
            while (done < length) {
                int read = read(position + done, target, done, length - done);
                if (read < 0) throw new IOException("unexpected end of archive at byte " + (position + done));
                done += read;
            }
        }

        static Source of(Path file) throws IOException {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            return new Source() {
                @Override
                public long size() throws IOException {
                    return channel.size();
                }

                @Override
                public int read(long position, byte[] target, int offset, int length) throws IOException {
                    return channel.read(ByteBuffer.wrap(target, offset, length), position);
                }

                @Override
                public void close() throws IOException {
                    channel.close();
                }
            };
        }

        static Source of(byte[] bytes) {
            return new Source() {
                @Override
                public long size() {
                    return bytes.length;
                }

                @Override
                public int read(long position, byte[] target, int offset, int length) {
                    if (position >= bytes.length) return -1;
                    int count = (int) Math.min(length, bytes.length - position);
                    System.arraycopy(bytes, (int) position, target, offset, count);
                    return count;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static int uint16(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    private static long uint32(byte[] data, int at) {
        return (uint16(data, at) | (long) uint16(data, at + 2) << 16) & 0xFFFFFFFFL;
    }

    private static int int32(byte[] data, int at) {
        return (int) uint32(data, at);
    }

    private static long int64(byte[] data, int at) {
        return uint32(data, at) | uint32(data, at + 4) << 32;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** An entry name safe for a log line or a prompt. */
    private static String printable(String name) {
        String shown = name.length() > 120 ? name.substring(0, 117) + "..." : name;
        return ZipSecurity.sanitizeManifestValue(shown);
    }

    /** A size in the units the prompt and the log use, such as {@code 31.2 GB}. */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }
}
