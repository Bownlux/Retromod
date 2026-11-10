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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void debugIsDocumentedAsActiveSetting() throws Exception {
        // Fabric and Quilt run a reference check when debug is true, so it is not reserved.
        String[] sections = Files.readString(Path.of("docs/config.md")).split("## Reserved Settings");

        assertEquals(2, sections.length, "docs/config.md needs exactly one Reserved Settings section");
        assertTrue(sections[0].contains("| `debug` |"), "debug belongs under Active Settings");
        assertFalse(sections[1].contains("| `debug` |"), "debug must not be listed as reserved");
    }

    @Test
    void everyGeneratedConfigKeyIsDocumented() throws Exception {
        JsonObject generated = RetromodConfig.parseConfig(RetromodConfig.DEFAULT_CONFIG);
        String docs = Files.readString(Path.of("docs/config.md"));

        // Keys starting with _ are comments inside the file, not settings.
        for (String key : generated.keySet()) {
            if (key.startsWith("_")) continue;
            assertTrue(docs.contains("| `" + key + "` |"),
                    "docs/config.md has no table row for the generated key " + key);
        }
    }

    @Test
    void newConfigStatesTheInPlaceSwitchAndTurnsVerificationOn() throws Exception {
        JsonObject generated = RetromodConfig.parseConfig(RetromodConfig.DEFAULT_CONFIG);

        // A new file spells out in-place mode and turns verification on; a missing key leaves it off.
        assertFalse(generated.get("write_translated_jars").getAsBoolean());
        assertTrue(generated.get("verify_transforms").getAsBoolean());
    }
}
