/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void configParsingRefusesDeepTrees() {
        String json = "[".repeat(257) + "0" + "]".repeat(257);

        assertThrows(Exception.class, () -> RetromodConfig.parseConfig(json));
    }

    @Test
    void explicitConfigPathRefusesDeepTrees(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.json");
        Files.writeString(config, "{\"verify_transforms\":true,\"padding\":"
                + "[".repeat(257) + "0" + "]".repeat(257) + "}");

        assertFalse(RetromodConfig.getBooleanIfPresent(
                config, "verify_transforms", false));
    }
}
