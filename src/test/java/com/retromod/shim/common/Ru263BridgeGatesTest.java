/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The removed-class bridges for TryFindWaterGoal and VariantHolder register only where the host lost the class. */
class Ru263BridgeGatesTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("A readable host decides; offline the target does")
    void followTheHost() {
        assertTrue(LegacyTryFindWaterGoalBridge.hostRemovedIt(true, false));
        assertFalse(LegacyTryFindWaterGoalBridge.hostRemovedIt(true, true));
        assertTrue(LegacyVariantHolderBridge.hostRemovedIt(true, false));
        assertFalse(LegacyVariantHolderBridge.hostRemovedIt(true, true));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(LegacyTryFindWaterGoalBridge.hostRemovedIt(false, false), "26.2 still has the goal");
        assertTrue(LegacyVariantHolderBridge.hostRemovedIt(false, false), "26.x has no VariantHolder");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyTryFindWaterGoalBridge.hostRemovedIt(false, false));
        RetromodVersion.TARGET_MC_VERSION = "1.21.5";
        assertTrue(LegacyVariantHolderBridge.hostRemovedIt(false, false), "1.21.5 removed VariantHolder");
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        assertFalse(LegacyVariantHolderBridge.hostRemovedIt(false, false), "1.21.4 still has it");
    }
}
