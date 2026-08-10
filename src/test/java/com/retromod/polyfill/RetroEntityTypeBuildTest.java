/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetroEntityTypeBuildTest {

    @Test
    @DisplayName("ResourceKey rendering with a closing bracket identifies ENTITY_TYPE")
    void entityTypeRootStringRecognized() {
        assertTrue(RetroEntityTypeBuild.isEntityTypeRoot(
                "ResourceKey[minecraft:root / minecraft:entity_type]"));
        assertFalse(RetroEntityTypeBuild.isEntityTypeRoot(
                "ResourceKey[minecraft:root / minecraft:point_of_interest_type]"));
    }
}
