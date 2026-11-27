/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** The per-file and class limits every unpacking loop applies to the jar it translates. */
class ArchiveLimitsTest {

    @Test
    @DisplayName("a file unpacked for the steps stays within 128 MiB, and a class within 32 MiB, whatever the total")
    void unpackedFilesKeepTheirLimit() {
        long total = 20L << 30;

        assertEquals(ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, ArchiveLimits.entryLimit("big.accesswidener", total));
        assertEquals(ZipSecurity.MAX_CLASS_ENTRY_SIZE, ArchiveLimits.entryLimit("a/A.class", total));
        assertEquals(1000, ArchiveLimits.entryLimit("big.accesswidener", 1000), "the total still bounds a file");
        assertEquals(0, ArchiveLimits.entryLimit("a/A.class", -5));
    }

    @Test
    @DisplayName("a jar's classes are counted together, and only classes count")
    void classBytesAreCountedTogether() throws IOException {
        ArchiveLimits.ClassBytes classes = new ArchiveLimits.ClassBytes("mod.jar", 1000);
        classes.add("assets/mod/big.png", 5000);
        classes.add("a/A.class", 600);

        IOException full = assertThrows(IOException.class, () -> classes.add("a/B.class", 600));
        assertTrue(full.getMessage().contains("mod.jar") && full.getMessage().contains("1000 bytes"),
                full.getMessage());
    }

    @Test
    @DisplayName("outside a translated jar, readers keep the 500 MB cap they always had")
    void defaultCapOutsideAScope() {
        assertEquals(ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, ArchiveLimits.maxTotalBytes());
        try (ArchiveLimits.Scope ignored = ArchiveLimits.enter(
                new ArchiveLimits.Allowance(ArchiveLimits.NORMAL_TOTAL_BYTES, false))) {
            assertEquals(ArchiveLimits.NORMAL_TOTAL_BYTES, ArchiveLimits.maxTotalBytes());
        }
    }
}
