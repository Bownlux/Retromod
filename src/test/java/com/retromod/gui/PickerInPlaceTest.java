/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.inplace.InPlaceLaunch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The file picker follows the mode this launch runs, not the saved setting. In-place mode puts
 * the chosen Forge or NeoForge jar into mods/ unchanged; everywhere else the picker keeps
 * writing translated jars as before.
 */
class PickerInPlaceTest {

    private static Properties mode(String value) {
        Properties properties = new Properties();
        if (value != null) properties.setProperty("retromod.inplace.mode", value);
        return properties;
    }

    @Test
    @DisplayName("Forge-family jars go through in-place loading only while it runs")
    void forgeFamilyJarsFollowTheModeInEffect() {
        assertTrue(ModCompatibilityChecker.handledInPlace("forge", mode("in-place")));
        assertTrue(ModCompatibilityChecker.handledInPlace("neoforge", mode("in-place")));
        assertTrue(ModCompatibilityChecker.handledInPlace("forge", mode("unavailable")),
                "in-place mode is still the setting, so mods/ gets no translated jar");
        assertFalse(ModCompatibilityChecker.handledInPlace("forge", mode("rewrite")));
        assertFalse(ModCompatibilityChecker.handledInPlace("forge", mode(null)),
                "without the bootstrap (Fabric, Quilt) the picker is unchanged");
        assertFalse(ModCompatibilityChecker.handledInPlace("fabric", mode("in-place")),
                "Fabric jars on NeoForge keep the earlier picker path");
    }

    @Test
    @DisplayName("The setup text and result lines describe what in-place mode does")
    void inPlaceTextMatchesBehavior() {
        assertTrue(RetromodGui.firstRunText(true).contains("unchanged"));
        assertTrue(RetromodGui.firstRunText(false).contains("installs updated copies"));
        assertTrue(RetromodGui.inPlaceResultLine("a.jar",
                InPlaceLaunch.ChosenOutcome.NEXT_LAUNCH).contains("one more restart"));
        assertTrue(RetromodGui.inPlaceResultLine("a.jar",
                InPlaceLaunch.ChosenOutcome.FAILED).contains("logs/latest.log"));
    }
}
