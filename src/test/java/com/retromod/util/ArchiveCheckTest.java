/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The archive check passes real jars and stops the shapes zip bombs are built from. */
class ArchiveCheckTest {

    @TempDir
    Path tmp;

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private Path file(String name, byte[] bytes) throws IOException {
        return Files.write(tmp.resolve(name), bytes);
    }

    private static void assertFails(ArchiveCheck.Result result, String expected) {
        assertFalse(result.passed(), "the check should fail with: " + expected);
        assertTrue(result.problem().contains(expected),
                "the reason should name " + expected + ", got: " + result.problem());
    }

    @Test
    @DisplayName("a jar written like a real mod, with a nested jar, passes and is counted")
    void realJarPasses() throws IOException {
        byte[] nested = RawZip.jar(new String[]{"lib/Lib.class", "lib.json"},
                new byte[][]{new byte[300], text("{\"id\":\"lib\"}")});
        Path jar = file("mod.jar", RawZip.jar(
                new String[]{"META-INF/mods.toml", "mod/Main.class", "assets/mod/lang/en_us.json",
                        "META-INF/jarjar/lib.jar"},
                new byte[][]{text("modLoader=\"javafml\""), new byte[2000], text("{}"), nested}));

        ArchiveCheck.Result result = ArchiveCheck.checkContent(jar);

        assertTrue(result.passed(), "a real jar passes: " + result.problem());
        assertTrue(result.contentCounted());
        assertEquals(4, result.entries());
        assertEquals(ArchiveCheck.readTotals(jar).unpackedBytes(), result.unpackedBytes());
    }

    @Test
    @DisplayName("a large entry that unpacks over 100 times its stored size fails the header check")
    void highRatioEntryFails() throws IOException {
        Path bomb = new RawZip().add(RawZip.deflated("big.bin", new byte[5 << 20])).write(tmp.resolve("ratio.jar"));

        ArchiveCheck.Result result = ArchiveCheck.checkStructure(bomb);

        assertFails(result, "more than 100 times its stored size");
        assertTrue(Files.size(bomb) < 64 * 1024, "the fixture stays small on disk");
    }

    @Test
    @DisplayName("many small entries that together unpack over 20 times their size fail")
    void highOverallRatioFails() throws IOException {
        RawZip zip = new RawZip();
        for (int i = 0; i < 10; i++) zip.add(RawZip.deflated("part" + i + ".bin", new byte[1 << 20]));

        assertFails(ArchiveCheck.checkStructure(zip.write(tmp.resolve("overall.jar"))),
                "more than 20 times its stored size");
    }

    @Test
    @DisplayName("two entries whose records overlap, the non-recursive zip bomb, fail")
    void overlappingEntriesFail() throws IOException {
        RawZip.Entry kernel = RawZip.stored("b.txt", text("shared kernel"));
        // The first entry's data holds a complete record of the second, which the central
        // directory points at, so the two overlap in the file.
        RawZip.Entry outer = RawZip.stored("a.bin", RawZip.localRecord(kernel));
        Path bomb = new RawZip().add(outer).add(kernel.pointingInto(0, 0)).write(tmp.resolve("overlap.jar"));

        assertFails(ArchiveCheck.checkStructure(bomb), "overlap in the file");
    }

    @Test
    @DisplayName("an entry listed twice fails, because readers could pick different copies")
    void duplicateNamesFail() throws IOException {
        Path jar = new RawZip().add(RawZip.stored("a.txt", text("one")))
                .add(RawZip.stored("a.txt", text("two"))).write(tmp.resolve("duplicate.jar"));

        assertFails(ArchiveCheck.checkStructure(jar), "appears twice");
    }

    @Test
    @DisplayName("a local header that names another file than the central directory fails")
    void headerMismatchFails() throws IOException {
        Path jar = new RawZip().add(RawZip.stored("a.txt", text("data")).withLocalName("b.txt"))
                .write(tmp.resolve("mismatch.jar"));

        assertFails(ArchiveCheck.checkStructure(jar), "different name in its local header");
    }

    @Test
    @DisplayName("an entry that unpacks past its declared size passes the headers but fails the count")
    void lyingSizeFailsTheCount() throws IOException {
        byte[] content = new byte[20_000];
        Arrays.fill(content, (byte) 'a');
        Path jar = new RawZip().add(RawZip.deflated("data.json", content).declaring(100))
                .write(tmp.resolve("lying.jar"));

        assertTrue(ArchiveCheck.checkStructure(jar).passed(), "the headers agree with each other");
        assertFails(ArchiveCheck.checkContent(jar), "unpacks past the 100 bytes");
    }

    @Test
    @DisplayName("a nested jar that fails the check fails the jar that holds it")
    void nestedBombFails() throws IOException {
        byte[] inner = new RawZip().add(RawZip.deflated("big.bin", new byte[5 << 20])).bytes();
        Path jar = file("outer.jar", RawZip.jar(new String[]{"META-INF/jars/inner.jar"},
                new byte[][]{inner}));

        assertTrue(ArchiveCheck.checkStructure(jar).passed(), "the outer headers are fine");
        assertFails(ArchiveCheck.checkContent(jar), "nested archive META-INF/jars/inner.jar");
    }

    @Test
    @DisplayName("nested archives are checked down to the depth cap, below which they are only bytes")
    void nestedDepthIsCapped() throws IOException {
        byte[] level = new RawZip().add(RawZip.deflated("big.bin", new byte[5 << 20])).bytes();
        // The bomb sits one level below the deepest archive the transforms open.
        for (int depth = 0; depth <= ArchiveCheck.MAX_NESTED_DEPTH; depth++) {
            level = RawZip.jar(new String[]{"inner.jar"}, new byte[][]{level});
        }
        assertTrue(ArchiveCheck.checkContent(file("deep.jar", level)).passed(),
                "no transform unpacks that level, so it is copied as bytes");

        byte[] shallow = new RawZip().add(RawZip.deflated("big.bin", new byte[5 << 20])).bytes();
        for (int depth = 1; depth < ArchiveCheck.MAX_NESTED_DEPTH; depth++) {
            shallow = RawZip.jar(new String[]{"inner.jar"}, new byte[][]{shallow});
        }
        assertFails(ArchiveCheck.checkContent(file("shallow.jar", shallow)), "nested archive inner.jar");
    }

    @Test
    @DisplayName("a file that only ends in .zip is not an archive and passes")
    void nonArchiveZipNamePasses() throws IOException {
        Path jar = file("readme.jar", RawZip.jar(new String[]{"assets/mod/notes.zip"},
                new byte[][]{text("not a zip at all")}));

        assertTrue(ArchiveCheck.checkContent(jar).passed());
    }

    @Test
    @DisplayName("a class file declared larger than any real class fails before its data is read")
    void oversizedClassFails() throws IOException {
        Path jar = new RawZip().add(RawZip.deflated("mod/Huge.class", new byte[64]).declaring(40L << 20))
                .write(tmp.resolve("class.jar"));

        assertFails(ArchiveCheck.checkStructure(jar), "class files are never larger than");
    }

    @Test
    @DisplayName("encrypted entries and unknown compression methods fail with a reason")
    void unreadableEntriesFail() throws IOException {
        Path encrypted = new RawZip().add(RawZip.stored("a.txt", text("x")).flags(1))
                .write(tmp.resolve("encrypted.jar"));
        assertFails(ArchiveCheck.checkStructure(encrypted), "is encrypted");
    }

    @Test
    @DisplayName("ZIP64 sizes and offsets in extra fields are read like the plain ones")
    void zip64FieldsAreRead() throws IOException {
        Path jar = new RawZip().add(RawZip.stored("first.txt", text("plain")))
                .add(RawZip.deflated("second.txt", text("zip64 sized entry")).zip64())
                .write(tmp.resolve("zip64.jar"));

        ArchiveCheck.Result result = ArchiveCheck.checkContent(jar);

        assertTrue(result.passed(), "ZIP64 records are valid: " + result.problem());
        assertEquals("plain".length() + "zip64 sized entry".length(), result.unpackedBytes());
    }

    @Test
    @DisplayName("a jar with more entries than the classic end record holds uses ZIP64 and is read")
    void zip64EndRecordIsRead() throws IOException {
        Path jar = tmp.resolve("many.jar");
        try (var out = new java.util.jar.JarOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(jar)))) {
            // Empty stored entries keep the fixture quick to write and a few megabytes on disk.
            for (int i = 0; i < 70_000; i++) {
                java.util.jar.JarEntry entry = new java.util.jar.JarEntry("d/" + i);
                entry.setMethod(java.util.zip.ZipEntry.STORED);
                entry.setSize(0);
                entry.setCompressedSize(0);
                entry.setCrc(0);
                out.putNextEntry(entry);
                out.closeEntry();
            }
        }

        ArchiveCheck.Result result = ArchiveCheck.checkStructure(jar);

        assertTrue(result.passed(), result.problem());
        assertEquals(70_000, result.entries());
    }

    /** Limits small enough for a test fixture to reach each one. */
    private static ArchiveCheck.Limits limits(long nestedInflate, int entries, long nestedArchive,
            long nestedDirectory, long classes) {
        return new ArchiveCheck.Limits(nestedInflate, entries, nestedArchive, nestedDirectory, classes);
    }

    private static final ArchiveCheck.Limits DEFAULTS = ArchiveCheck.Limits.DEFAULT;

    /** A zip of {@code fanout} copies of {@code child}, nested {@code levels} times over a leaf of zeros. */
    private static byte[] nestedCopies(int fanout, int levels, int leafBytes) throws IOException {
        byte[] level = RawZip.jar(new String[]{"leaf.bin"}, new byte[][]{new byte[leafBytes]});
        for (int depth = 0; depth < levels; depth++) {
            String[] names = new String[fanout];
            byte[][] contents = new byte[fanout][];
            for (int i = 0; i < fanout; i++) {
                names[i] = "copy" + depth + "_" + i + ".jar";
                contents[i] = level;
            }
            level = RawZip.jar(names, contents);
        }
        return level;
    }

    @Test
    @DisplayName("the check picks the same end record Java's zip reader picks")
    void endRecordMatchesJavaZip() throws IOException {
        byte[] data = new byte[1 << 20];
        RawZip.Entry shared = RawZip.deflated("b.bin", data);
        byte[] small = text("hello");
        // A first directory lists two names for one local record; a second, inside the first end
        // record's comment, lists one small file.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write('X');
        long smallOffset = out.size();
        out.writeBytes(RawZip.localRecord(RawZip.stored("a.txt", small)));
        long sharedOffset = out.size();
        out.writeBytes(RawZip.localRecord(shared));
        long firstDirectory = out.size();
        out.writeBytes(centralRecord("b.bin", shared, sharedOffset));
        out.writeBytes(centralRecord("c.bin", shared, sharedOffset));
        long firstDirectorySize = out.size() - firstDirectory;
        java.io.ByteArrayOutputStream comment = new java.io.ByteArrayOutputStream();
        long secondDirectory = out.size() + 22;
        comment.writeBytes(centralRecord("a.txt", RawZip.stored("a.txt", small), smallOffset));
        long secondDirectorySize = comment.size();
        comment.writeBytes(endRecord(1, secondDirectorySize, secondDirectory, 0));
        comment.write('Z');
        out.writeBytes(endRecord(2, firstDirectorySize, firstDirectory, comment.size()));
        out.writeBytes(comment.toByteArray());
        Path jar = file("two-ends.jar", out.toByteArray());

        ArchiveCheck.Result result = ArchiveCheck.checkContent(jar);

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            assertEquals(zip.size(), result.entries(), "both readers see the same directory");
        }
        assertFails(result, "has a different name in its local header");
    }

    @Test
    @DisplayName("the check fails when Java's zip reader lists other entries than the headers")
    void javaZipListingMustMatch() throws IOException {
        Path jar = file("plain.jar", RawZip.jar(new String[]{"a.txt", "b.txt"},
                new byte[][]{text("one"), text("two")}));
        ArchiveCheck.Totals totals = ArchiveCheck.readTotals(jar);

        assertNull(ArchiveCheck.differsFromJavaZip(jar, totals), "a jar both readers agree on");
        assertNotNull(ArchiveCheck.differsFromJavaZip(jar,
                new ArchiveCheck.Totals(totals.entries() + 1, totals.unpackedBytes(), totals.storedBytes())));
        assertNotNull(ArchiveCheck.differsFromJavaZip(jar,
                new ArchiveCheck.Totals(totals.entries(), totals.unpackedBytes() + 1, totals.storedBytes())));
    }

    @Test
    @DisplayName("nested archives share one inflate budget across every level")
    void nestedArchivesShareOneInflateBudget() throws IOException {
        // 4 x 4 x 4 copies of a 64 KiB leaf unpack to 4 MiB, from a file of a few KiB.
        Path jar = file("copies.jar", nestedCopies(4, 3, 64 << 10));

        assertTrue(ArchiveCheck.checkContent(jar).passed(), "within the default budget it passes");
        assertFails(ArchiveCheck.checkContent(jar, limits(1 << 20, 100_000,
                DEFAULTS.nestedArchiveBytes(), DEFAULTS.nestedDirectoryBytes(), DEFAULTS.classBytes())),
                "unpack to more than 1.0 MB together");
    }

    @Test
    @DisplayName("a jar and its nested archives share one entry limit")
    void nestedEntriesCountTowardOneLimit() throws IOException {
        Path jar = file("entries.jar", nestedCopies(3, 2, 10));

        assertTrue(ArchiveCheck.checkContent(jar).passed());
        assertFails(ArchiveCheck.checkContent(jar, limits(DEFAULTS.nestedInflateBytes(), 10,
                DEFAULTS.nestedArchiveBytes(), DEFAULTS.nestedDirectoryBytes(), DEFAULTS.classBytes())),
                "hold more than 10 entries together");
    }

    @Test
    @DisplayName("a nested jar over the nested limit fails, and a large archive elsewhere is counted as bytes")
    void nestedJarSizeIsLimited() throws IOException {
        // Varied bytes keep the nested jar larger than the test limit.
        byte[] varied = new byte[4000];
        new java.util.Random(1).nextBytes(varied);
        byte[] inner = RawZip.jar(new String[]{"lib/Lib.class"}, new byte[][]{varied});
        ArchiveCheck.Limits small = limits(DEFAULTS.nestedInflateBytes(), DEFAULTS.maxEntries(), 1000,
                DEFAULTS.nestedDirectoryBytes(), DEFAULTS.classBytes());

        Path translated = file("nested.jar", RawZip.jar(new String[]{"META-INF/jars/lib.jar"}, new byte[][]{inner}));
        assertFails(ArchiveCheck.checkContent(translated, small), "translates nested jars up to");

        Path copied = file("asset.jar", RawZip.jar(new String[]{"assets/mod/pack.zip"}, new byte[][]{inner}));
        assertTrue(ArchiveCheck.checkContent(copied, small).passed(),
                "an archive the transforms only copy is not opened");
    }

    @Test
    @DisplayName("a nested archive's central directory has a lower limit than the jar's")
    void nestedDirectoryIsLimited() throws IOException {
        byte[] inner = RawZip.jar(new String[]{"a.txt", "b.txt", "c.txt"},
                new byte[][]{text("a"), text("b"), text("c")});
        Path jar = file("dir.jar", RawZip.jar(new String[]{"META-INF/jars/lib.jar"}, new byte[][]{inner}));

        assertTrue(ArchiveCheck.checkContent(jar).passed());
        assertFails(ArchiveCheck.checkContent(jar, limits(DEFAULTS.nestedInflateBytes(), DEFAULTS.maxEntries(),
                DEFAULTS.nestedArchiveBytes(), 64, DEFAULTS.classBytes())), "central directory is");
    }

    @Test
    @DisplayName("a jar's class files share one size limit")
    void classFilesShareOneLimit() throws IOException {
        Path jar = file("classes.jar", RawZip.jar(new String[]{"a/A.class", "a/B.class", "a/C.class"},
                new byte[][]{new byte[500], new byte[500], new byte[500]}));
        ArchiveCheck.Limits small = limits(DEFAULTS.nestedInflateBytes(), DEFAULTS.maxEntries(),
                DEFAULTS.nestedArchiveBytes(), DEFAULTS.nestedDirectoryBytes(), 1000);

        assertTrue(ArchiveCheck.checkContent(jar).passed());
        assertFails(ArchiveCheck.checkContent(jar, small), "class files unpack to");
    }

    private static byte[] centralRecord(String name, RawZip.Entry entry, long offset) {
        byte[] nameBytes = text(name);
        java.nio.ByteBuffer record = java.nio.ByteBuffer.allocate(46 + nameBytes.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        record.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0)
                .putShort((short) entry.method).putInt(0).putInt((int) entry.crc)
                .putInt(entry.stored.length).putInt((int) entry.declaredSize)
                .putShort((short) nameBytes.length).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) offset).put(nameBytes);
        return record.array();
    }

    private static byte[] endRecord(int entries, long directorySize, long directoryOffset, int commentLength) {
        java.nio.ByteBuffer record = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        record.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) entries)
                .putShort((short) entries).putInt((int) directorySize).putInt((int) directoryOffset)
                .putShort((short) commentLength);
        return record.array();
    }

    @Test
    @DisplayName("damaged bytes are reported as a failed check, never an exception")
    void notAZipFails() throws IOException {
        Path jar = file("broken.jar", text("this is not a zip file, just text that is long enough"));

        assertFails(ArchiveCheck.checkStructure(jar), "no readable central directory");
        assertThrows(IOException.class, () -> ArchiveCheck.readTotals(jar));
    }
}
