/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.inplace.CacheTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliExportNoteTest {

    @Test
    @DisplayName("every export says the other game needs Retromod and the same loader and version")
    void exportNoteNamesTheLimits() {
        String note = RetromodCli.exportNote(new CacheTools.Host("neoforge", "26.3"));
        assertTrue(note.contains("NeoForge on Minecraft 26.3"), note);
        assertTrue(note.contains("Retromod for that loader and version"), note);
        assertTrue(note.contains("mods/"), note);
    }
}
