/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void boundedCopyNamesTheOversizedEntry() throws IOException {
        IOException failure = assertThrows(IOException.class, () -> ZipSecurity.copyBounded(
                new ByteArrayInputStream(new byte[11]), tempDir.resolve("big.bin"), 10,
                "assets/example/big.ogg"));

        // Every in-game extract loop caps entries here, so this is the phrase users find in latest.log.
        assertTrue(failure.getMessage().contains("ZIP entry too large"),
                "docs/troubleshooting.md quotes `ZIP entry too large`, but the message was: "
                        + failure.getMessage());
        assertTrue(failure.getMessage().contains("assets/example/big.ogg"),
                "the message should name the oversized entry: " + failure.getMessage());
        assertTroubleshootingQuotes("ZIP entry too large");
    }

    @Test
    void boundedReadReportsTheEntryLimit() throws IOException {
        IOException failure = assertThrows(IOException.class,
                () -> ZipSecurity.safeReadAllBytes(new ByteArrayInputStream(new byte[11]), 10));

        // The CLI reads every entry through this method.
        assertTrue(failure.getMessage().contains("ZIP entry exceeds"),
                "docs/troubleshooting.md quotes `ZIP entry exceeds`, but the message was: "
                        + failure.getMessage());
        assertTroubleshootingQuotes("ZIP entry exceeds");
    }

    /** Fails when the troubleshooting page no longer tells users to search for this log phrase. */
    private static void assertTroubleshootingQuotes(String phrase) throws IOException {
        String page = Files.readString(Path.of("docs/troubleshooting.md"));
        assertTrue(page.contains("`" + phrase + "`"),
                "docs/troubleshooting.md should quote the log phrase `" + phrase + "` under "
                        + "A Very Large Mod Is Skipped, so users can search latest.log for it");
    }

    @Test
    void manifestValuesCannotCreateAdditionalHeaders() {
        assertEquals("source.jar__Injected: yes_",
                ZipSecurity.sanitizeManifestValue(
                        "source.jar\r\nInjected: yes\u0000"));
        assertEquals("26.2", ZipSecurity.sanitizeManifestValue("26.2"));
    }
}
