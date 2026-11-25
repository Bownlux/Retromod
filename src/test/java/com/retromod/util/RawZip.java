/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.jar.JarOutputStream;

/** Writes small zip files byte by byte, so a test can build the malformed shapes zip bombs use. */
final class RawZip {

    /** One entry as written; the setters bend it into a malformed shape. */
    static final class Entry {
        final String name;
        final int method;
        final byte[] stored;
        final long crc;
        long declaredSize;
        String localName;
        Integer pointsAtEntry;
        long pointerShift;
        boolean zip64;
        int flags;

        private Entry(String name, int method, byte[] stored, long crc, long declaredSize) {
            this.name = name;
            this.method = method;
            this.stored = stored;
            this.crc = crc;
            this.declaredSize = declaredSize;
            this.localName = name;
        }

        /** Declares another unpacked size in both headers. */
        Entry declaring(long size) {
            declaredSize = size;
            return this;
        }

        /** Writes a different name into the local header only. */
        Entry withLocalName(String other) {
            localName = other;
            return this;
        }

        /** Points the central record at another entry's data, shifted by {@code shift} bytes. */
        Entry pointingInto(int entryIndex, long shift) {
            pointsAtEntry = entryIndex;
            pointerShift = shift;
            return this;
        }

        /** Moves sizes and offset into ZIP64 extra fields. */
        Entry zip64() {
            zip64 = true;
            return this;
        }

        Entry flags(int value) {
            flags = value;
            return this;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    static Entry stored(String name, byte[] content) {
        return new Entry(name, 0, content, crc(content), content.length);
    }

    static Entry deflated(String name, byte[] content) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(content);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return new Entry(name, 8, out.toByteArray(), crc(content), content.length);
    }

    RawZip add(Entry entry) {
        entries.add(entry);
        return this;
    }

    /** The local header and data of one entry, for embedding it inside another. */
    static byte[] localRecord(Entry entry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLocal(out, entry);
        return out.toByteArray();
    }

    byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] offsets = new long[entries.size()];
        long[] dataStarts = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            offsets[i] = out.size();
            Entry entry = entries.get(i);
            dataStarts[i] = offsets[i] + 30 + entry.localName.getBytes(StandardCharsets.UTF_8).length
                    + (entry.zip64 ? 20 : 0);
            writeLocal(out, entry);
        }
        long directoryStart = out.size();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            long offset = entry.pointsAtEntry == null ? offsets[i]
                    : dataStarts[entry.pointsAtEntry] + entry.pointerShift;
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            int32(out, 0x02014b50);
            int16(out, 20);
            int16(out, entry.zip64 ? 45 : 20);
            int16(out, entry.flags);
            int16(out, entry.method);
            int32(out, 0);
            int32(out, (int) entry.crc);
            int32(out, entry.zip64 ? -1 : entry.stored.length);
            int32(out, entry.zip64 ? -1 : (int) entry.declaredSize);
            int16(out, name.length);
            int16(out, entry.zip64 ? 28 : 0);
            int16(out, 0);
            int16(out, 0);
            int16(out, 0);
            int32(out, 0);
            int32(out, entry.zip64 ? -1 : (int) offset);
            out.writeBytes(name);
            if (entry.zip64) {
                int16(out, 1);
                int16(out, 24);
                int64(out, entry.declaredSize);
                int64(out, entry.stored.length);
                int64(out, offset);
            }
        }
        long directorySize = out.size() - directoryStart;
        int32(out, 0x06054b50);
        int16(out, 0);
        int16(out, 0);
        int16(out, entries.size());
        int16(out, entries.size());
        int32(out, (int) directorySize);
        int32(out, (int) directoryStart);
        int16(out, 0);
        return out.toByteArray();
    }

    Path write(Path file) throws IOException {
        return Files.write(file, bytes());
    }

    private static void writeLocal(ByteArrayOutputStream out, Entry entry) {
        byte[] name = entry.localName.getBytes(StandardCharsets.UTF_8);
        int32(out, 0x04034b50);
        int16(out, entry.zip64 ? 45 : 20);
        int16(out, entry.flags);
        int16(out, entry.method);
        int32(out, 0);
        int32(out, (int) entry.crc);
        int32(out, entry.zip64 ? -1 : entry.stored.length);
        int32(out, entry.zip64 ? -1 : (int) entry.declaredSize);
        int16(out, name.length);
        int16(out, entry.zip64 ? 20 : 0);
        out.writeBytes(name);
        if (entry.zip64) {
            int16(out, 1);
            int16(out, 16);
            int64(out, entry.declaredSize);
            int64(out, entry.stored.length);
        }
        out.writeBytes(entry.stored);
    }

    /** A jar written by the JDK, the shape real mod jars have. */
    static byte[] jar(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) {
                jar.putNextEntry(new java.util.jar.JarEntry(names[i]));
                jar.write(contents[i]);
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static long crc(byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        return crc.getValue();
    }

    private static void int16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void int32(ByteArrayOutputStream out, int value) {
        int16(out, value & 0xFFFF);
        int16(out, (value >>> 16) & 0xFFFF);
    }

    private static void int64(ByteArrayOutputStream out, long value) {
        int32(out, (int) value);
        int32(out, (int) (value >>> 32));
    }
}
