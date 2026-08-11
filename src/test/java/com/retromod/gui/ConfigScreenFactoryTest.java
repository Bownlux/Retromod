/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigScreenFactoryTest {

    @Test
    void guiSavePreservesManualNetworkConsentAndUnknownSettings() {
        String existing = """
                {
                  "_network_comment": "Manual opt-in only",
                  "check_for_native_versions": true,
                  "transform_mixins": false,
                  "future_setting": "keep me",
                  "verify_transforms": true
                }
                """;
        Map<String, Boolean> toggles = new LinkedHashMap<>();
        toggles.put("verify_transforms", false);
        toggles.put("polyfills_enabled", true);

        var root = JsonParser.parseString(
                ConfigScreenFactory.mergeConfigJson(existing, toggles)).getAsJsonObject();

        assertTrue(root.get("check_for_native_versions").getAsBoolean());
        assertEquals("Manual opt-in only", root.get("_network_comment").getAsString());
        assertEquals("keep me", root.get("future_setting").getAsString());
        assertFalse(root.get("transform_mixins").getAsBoolean());
        assertFalse(root.get("verify_transforms").getAsBoolean());
        assertTrue(root.get("polyfills_enabled").getAsBoolean());
    }
}
