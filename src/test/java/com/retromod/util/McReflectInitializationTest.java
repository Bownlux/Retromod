/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McReflectInitializationTest {

    private static final class InitializationState {
        private static boolean initialized;
    }

    private static final class ProbeFixture {
        static {
            InitializationState.initialized = true;
        }
    }

    @Test
    @DisplayName("Minecraft reflection probes find a class without running its static initializer")
    void probesDoNotInitializeClasses() {
        InitializationState.initialized = false;
        String fixtureName = McReflectInitializationTest.class.getName() + "$ProbeFixture";

        assertTrue(McReflect.classExists(fixtureName));
        assertNotNull(McReflect.findClass(fixtureName));
        assertFalse(InitializationState.initialized,
                "presence and resolution probes must not execute class initialization code");
    }
}
