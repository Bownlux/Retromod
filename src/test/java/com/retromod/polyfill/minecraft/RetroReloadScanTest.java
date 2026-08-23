/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetroReloadScanTest {

    @Test
    void boundedReadIgnoresStructureCharactersInsideStrings() throws Exception {
        String json = "{\"url\":\"https://example.invalid/[a]/{b}\",\"items\":[1,2]}";

        assertEquals(json, RetroReloadScan.readBoundedJson(
                new StringReader(json), 256, 4, new long[] {256}));
    }

    @Test
    void boundedReadRejectsExcessiveNesting() {
        String json = "[[[[[0]]]]]";

        assertThrows(IOException.class, () -> RetroReloadScan.readBoundedJson(
                new StringReader(json), 256, 4, new long[] {256}));
    }

    @Test
    void boundedReadRejectsOversizedResource() {
        assertThrows(IOException.class, () -> RetroReloadScan.readBoundedJson(
                new StringReader("{\"value\":123}"), 8, 4,
                new long[] {256}));
    }

    @Test
    void boundedReadSharesAnAggregateBudget() throws Exception {
        long[] budget = {9};
        assertEquals("[1,2]", RetroReloadScan.readBoundedJson(
                new StringReader("[1,2]"), 16, 4, budget));

        assertThrows(IOException.class, () -> RetroReloadScan.readBoundedJson(
                new StringReader("[3,4]"), 16, 4, budget));
    }
}
