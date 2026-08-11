/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetromodConfigTest {

    @Test
    void booleanSettingUsesConfiguredValue() {
        JsonObject config = new JsonObject();
        config.addProperty("polyfills_enabled", false);

        assertFalse(RetromodConfig.booleanValue(config, "polyfills_enabled", true));
    }

    @Test
    void booleanSettingFallsBackForMissingOrInvalidValues() {
        JsonObject config = new JsonObject();
        config.addProperty("polyfills_enabled", "not-a-boolean");

        assertTrue(RetromodConfig.booleanValue(null, "polyfills_enabled", true));
        assertFalse(RetromodConfig.booleanValue(config, "missing", false));
        assertTrue(RetromodConfig.booleanValue(config, "polyfills_enabled", true));
    }
}
