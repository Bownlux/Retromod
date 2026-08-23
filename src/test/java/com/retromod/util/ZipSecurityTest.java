/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZipSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsBrokenSymbolicLink() throws IOException {
        Path link = tempDir.resolve("broken-link");
        Files.createSymbolicLink(link, tempDir.resolve("missing-target"));

        assertThrows(IOException.class, () -> ZipSecurity.validateNotSymlink(link));
    }

    @Test
    void acceptsMissingOrdinaryPath() {
        assertDoesNotThrow(() -> ZipSecurity.validateNotSymlink(tempDir.resolve("missing")));
    }

    @Test
    void boundedCopyContinuesAfterZeroLengthRead() throws IOException {
        byte[] expected = new byte[]{1, 2, 3};
        InputStream input = new InputStream() {
            private int readCount;
            private int index;

            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (readCount++ == 0) return 0;
                if (index == expected.length) return -1;
                int count = Math.min(length, expected.length - index);
                System.arraycopy(expected, index, bytes, offset, count);
                index += count;
                return count;
            }

            @Override
            public int read() {
                return index == expected.length ? -1 : expected[index++];
            }
        };

        Path output = tempDir.resolve("output.bin");
        assertEquals(expected.length, ZipSecurity.copyBounded(input, output, 16, "test"));
        assertEquals(expected.length, Files.size(output));
    }

    @Test
    void manifestValuesCannotCreateAdditionalHeaders() {
        assertEquals("source.jar__Injected: yes_",
                ZipSecurity.sanitizeManifestValue(
                        "source.jar\r\nInjected: yes\u0000"));
        assertEquals("26.2", ZipSecurity.sanitizeManifestValue("26.2"));
    }
}
