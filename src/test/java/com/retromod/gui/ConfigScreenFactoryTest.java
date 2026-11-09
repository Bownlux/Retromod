/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodConfig;
import com.retromod.inplace.InPlaceSettings;
import com.retromod.security.SignatureVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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

    @Test
    void guiSaveRefusesDeepExistingConfigBeforeParsing() {
        String existing = "[".repeat(257) + "0" + "]".repeat(257);
        Map<String, Boolean> toggles = Map.of("verify_transforms", false);

        var root = JsonParser.parseString(
                ConfigScreenFactory.mergeConfigJson(existing, toggles)).getAsJsonObject();

        assertFalse(root.get("verify_transforms").getAsBoolean());
    }

    private static Properties props(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) properties.setProperty(pairs[i], pairs[i + 1]);
        return properties;
    }

    @Test
    void inPlaceSwitchIsHiddenWhereItChangesNothing() {
        assertFalse(ConfigScreenFactory.visibleToggleKeys(props())
                        .contains("write_translated_jars"),
                "Fabric and Quilt always write into mods/, so no toggle is shown there");
        assertTrue(ConfigScreenFactory.visibleToggleKeys(
                        props("retromod.boot.owner", "retromod.jar"))
                .contains("write_translated_jars"));
        assertEquals("write_translated_jars", ConfigScreenFactory.visibleToggleKeys(
                props("retromod.inplace.mode", "in-place")).get(2));
    }

    @Test
    void cacheToggleShowsOnlyWhereItsReaderRuns() {
        // Only the Fabric and Quilt entry point reads use_aot.
        assertTrue(ConfigScreenFactory.visibleToggleKeys(props()).contains("use_aot"));
        assertFalse(ConfigScreenFactory.visibleToggleKeys(
                        props("retromod.boot.owner", "retromod.jar")).contains("use_aot"),
                "Forge and NeoForge never read use_aot, so the row would change nothing there");
    }

    private static Path writeConfig(Path game, String json) throws Exception {
        Path config = RetromodConfig.configPath(game);
        Files.createDirectories(config.getParent());
        Files.writeString(config, json);
        return config;
    }

    @Test
    void spacedValuesReadLikeTheRuntime(@TempDir Path game) throws Exception {
        Path config = writeConfig(game,
                "{\"write_translated_jars\" : true, \"polyfills_enabled\":\tfalse}");

        Map<String, Boolean> shown = ConfigScreenFactory.loadConfig(config);

        assertTrue(shown.get("write_translated_jars"), "valid JSON spacing must not hide a value");
        assertFalse(shown.get("polyfills_enabled"));
        assertEquals(InPlaceSettings.resolve(game, new Properties()).writeTranslatedJars(),
                shown.get("write_translated_jars"), "the screen must agree with the discovery pass");
    }

    @Test
    void missingVerifySettingShowsOff(@TempDir Path game) throws Exception {
        Path config = writeConfig(game, "{}");
        assertFalse(ConfigScreenFactory.loadConfig(config).get("verify_transforms"),
                "the runtime leaves verification off when the key is absent");

        writeConfig(game, "{\"verify_transforms\": true}");
        assertTrue(ConfigScreenFactory.loadConfig(config).get("verify_transforms"));
    }

    @Test
    void savingOneToggleLeavesOtherKeysAlone(@TempDir Path game) throws Exception {
        Path config = writeConfig(game,
                "{\"write_translated_jars\" : true, \"verify_transforms\": \"yes\"}");

        ConfigScreenFactory.saveSetting(config, "polyfills_enabled", false);

        JsonObject saved = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(saved.get("write_translated_jars").getAsBoolean());
        assertEquals("yes", saved.get("verify_transforms").getAsString(),
                "a value the screen could not read must survive another toggle");
        assertFalse(saved.get("polyfills_enabled").getAsBoolean());
        assertFalse(saved.has("use_aot"), "only the pressed toggle may be written");
    }

    @Test
    void changedInPlaceSwitchSaysItWaitsForTheNextLaunch() {
        Map<String, Boolean> saved = new LinkedHashMap<>();
        saved.put("write_translated_jars", true);
        assertTrue(ConfigScreenFactory.switchWaitsForRestart(saved,
                props("retromod.inplace.mode", "in-place")));
        assertFalse(ConfigScreenFactory.switchWaitsForRestart(saved,
                props("retromod.inplace.mode", "rewrite")));
        saved.put("write_translated_jars", false);
        assertFalse(ConfigScreenFactory.switchWaitsForRestart(saved,
                props("retromod.inplace.mode", "in-place")));
        assertFalse(ConfigScreenFactory.switchWaitsForRestart(saved, props()),
                "without a discovery pass there is nothing to compare");
    }

    @Test
    void everyBuildStatusHasItsOwnTranslatedLine() throws Exception {
        JsonObject english = JsonParser.parseString(Files.readString(
                LangKeysTest.LANG.resolve("en_us.json"))).getAsJsonObject();
        Set<String> keys = new HashSet<>();

        for (SignatureVerifier.Status status : SignatureVerifier.Status.values()) {
            String key = ConfigScreenFactory.statusKey(status);
            assertTrue(keys.add(key), status + " shares its status line " + key);
            assertTrue(english.has(key), "en_us.json has no " + key + " for " + status);
        }
    }

    @Test
    void settingsDefaultToInPlaceMode(@TempDir Path game) {
        Path missing = RetromodConfig.configPath(game);

        assertFalse(ConfigScreenFactory.loadConfig(missing).get("write_translated_jars"));
        assertFalse(Files.exists(missing), "reading the settings must not create the file");
    }
}
