/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Draws Retromod's own two-layer spawn egg, for old eggs whose vanilla template 1.21.5 removed. */
final class SpawnEggTextures {

    static final String NAMESPACE = LegacyKeyCategoryNames.NAMESPACE;
    // Both layers are grayscale, so the two colors an item definition tints them with show as they
    // did on the old template: the first colors the shell, the second the spots.
    static final String BASE = NAMESPACE + ":item/spawn_egg_base";
    static final String SPOTS = NAMESPACE + ":item/spawn_egg_spots";
    static final String BASE_FILE = "assets/" + NAMESPACE + "/textures/item/spawn_egg_base.png";
    static final String SPOTS_FILE = "assets/" + NAMESPACE + "/textures/item/spawn_egg_spots.png";

    // The art is drawn here rather than shipped as image files, which keeps it reviewable as text.
    // In the shape, o is the outline and # the shell.
    private static final String[] SHAPE = {
            "................",
            ".......oo.......",
            "......o##o......",
            ".....o####o.....",
            ".....o####o.....",
            "....o######o....",
            "....o######o....",
            "...o########o...",
            "...o########o...",
            "...o########o...",
            "...o########o...",
            "...o########o...",
            "....o######o....",
            ".....o####o.....",
            "......oooo......",
            "................",
    };

    /** {@code x} marks a spot. Spots sit on the shell, never on the outline. */
    private static final String[] SPOT_PATTERN = {
            "................",
            "................",
            "................",
            ".......x........",
            "........x.......",
            "......xx........",
            ".........xx.....",
            ".....x...x......",
            "....xx..........",
            "..........xx....",
            ".......x...x....",
            "......xx........",
            ".........x......",
            ".......x........",
            "................",
            "................",
    };

    private SpawnEggTextures() {}

    /** The shell layer: a dark outline around a shell lit from the upper left. */
    static byte[] base() {
        int[] gray = new int[256];
        int[] alpha = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                char c = SHAPE[y].charAt(x);
                if (c == '.') continue;
                // The outline is dark gray. The shell starts near white at the upper left and
                // darkens toward the lower right, but never below a light gray.
                gray[y * 16 + x] = c == 'o' ? 96 : Math.max(176, 252 - 6 * Math.max(0, x - 5) - 4 * Math.max(0, y - 3));
                alpha[y * 16 + x] = 255;
            }
        }
        return png(gray, alpha);
    }

    /** The spots layer: white spots on the shell, transparent elsewhere. */
    static byte[] spots() {
        int[] gray = new int[256];
        int[] alpha = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (SPOT_PATTERN[y].charAt(x) == 'x' && SHAPE[y].charAt(x) == '#') {
                    gray[y * 16 + x] = 255;
                    alpha[y * 16 + x] = 255;
                }
            }
        }
        return png(gray, alpha);
    }

    /** A 16x16 8-bit grayscale-with-alpha PNG. */
    private static byte[] png(int[] gray, int[] alpha) {
        try {
            // Each row is a filter byte, then a gray and an alpha byte per pixel.
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            for (int y = 0; y < 16; y++) {
                raw.write(0); // no row filter
                for (int x = 0; x < 16; x++) {
                    raw.write(gray[y * 16 + x]);
                    raw.write(alpha[y * 16 + x]);
                }
            }
            // PNG image data is the rows compressed with zlib.
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed, deflater)) {
                raw.writeTo(deflate);
            } finally {
                deflater.end();
            }

            // The file is the PNG signature, then the header, data and end chunks.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            DataOutputStream fields = new DataOutputStream(header);
            fields.writeInt(16);
            fields.writeInt(16);
            fields.writeByte(8);  // bit depth
            fields.writeByte(4);  // grayscale with alpha
            fields.writeByte(0);  // deflate
            fields.writeByte(0);  // adaptive filtering
            fields.writeByte(0);  // not interlaced
            chunk(out, "IHDR", header.toByteArray());
            chunk(out, "IDAT", compressed.toByteArray());
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** Writes one PNG chunk: its length, type, data, and a CRC of the type and data. */
    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(out);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        stream.writeInt(data.length);
        stream.write(name);
        stream.write(data);
        stream.writeInt((int) crc.getValue());
    }
}
