/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreModPathSafetyTest {

    @Test
    void acceptsOnlySingleJarFileNames() {
        assertTrue(Retromod.isSafeModFileName("example.jar"));
        assertFalse(Retromod.isSafeModFileName("../config/example.jar"));
        assertFalse(Retromod.isSafeModFileName("..\\config\\example.jar"));
        assertFalse(Retromod.isSafeModFileName("folder/example.jar"));
        assertFalse(Retromod.isSafeModFileName("example.txt"));
        assertFalse(Retromod.isSafeModFileName(""));
    }
}
