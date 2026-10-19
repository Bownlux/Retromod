/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TransformContextTest {

    @TempDir
    Path game;

    private TransformContext context(String loader, String build, Properties properties) {
        return TransformContext.forGame(game, loader, build, properties);
    }

    private void writeAutoFixRules(String json) throws IOException {
        Path rules = game.resolve("config/retromod/auto-fixes.json");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, json);
    }

    private static Properties autoFixOn() {
        Properties properties = new Properties();
        properties.setProperty("retromod.autoFix", "true");
        return properties;
    }

    @Test
    @DisplayName("the same inputs always give the same hash")
    void hashIsStable() {
        assertEquals(context("neoforge", "21.1.252", new Properties()).hash(),
                context("neoforge", "21.1.252", new Properties()).hash());
        assertEquals(16, context("neoforge", "", new Properties()).hash().length());
    }

    @Test
    @DisplayName("a loader update under the same Minecraft version changes the hash")
    void loaderBuildChangesTheHash() {
        assertNotEquals(context("neoforge", "26.3.0.43-beta", new Properties()).hash(),
                context("neoforge", "26.3.0.44-beta", new Properties()).hash());
    }

    @Test
    @DisplayName("turning polyfills off changes the hash")
    void polyfillSettingChangesTheHash() throws IOException {
        String defaults = context("forge", "47.4.26", new Properties()).hash();
        Path config = game.resolve("config/retromod/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"polyfills_enabled\": true}");
        assertEquals(defaults, context("forge", "47.4.26", new Properties()).hash(),
                "an explicit true equals the default");
        Files.writeString(config, "{\"polyfills_enabled\": false}");
        assertNotEquals(defaults, context("forge", "47.4.26", new Properties()).hash());
    }

    @Test
    @DisplayName("settings that only report, not transform, leave the hash alone")
    void reportOnlySettingsDoNotChangeTheHash() throws IOException {
        String defaults = context("forge", "47.4.26", new Properties()).hash();
        Path config = game.resolve("config/retromod/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config,
                "{\"verify_transforms\": false, \"debug\": true, \"force_translate_complex\": true}");
        assertEquals(defaults, context("forge", "47.4.26", new Properties()).hash());
    }

    @Test
    @DisplayName("saved AutoFix rules count on Forge only when AutoFix is turned on")
    void autoFixRulesCountOnlyWhenApplied() throws IOException {
        String withoutRules = context("forge", "47.4.26", new Properties()).hash();
        writeAutoFixRules("{\"fixes\": [1]}");
        assertEquals(withoutRules, context("forge", "47.4.26", new Properties()).hash(),
                "Forge ignores the rule file unless -Dretromod.autoFix=true");

        String withRulesOn = context("forge", "47.4.26", autoFixOn()).hash();
        assertNotEquals(withoutRules, withRulesOn);
        writeAutoFixRules("{\"fixes\": [1, 2]}");
        assertNotEquals(withRulesOn, context("forge", "47.4.26", autoFixOn()).hash(),
                "a new rule must reach the next translation");
    }

    @Test
    @DisplayName("Fabric applies saved AutoFix rules without the flag, so they always count")
    void fabricAlwaysCountsAutoFixRules() throws IOException {
        String before = context("fabric", "0.19.5", new Properties()).hash();
        writeAutoFixRules("{\"fixes\": [1]}");
        assertNotEquals(before, context("fabric", "0.19.5", new Properties()).hash());
    }

    @Test
    @DisplayName("context values must be single-line text")
    void builderRefusesMultiLineValues() {
        assertThrows(IllegalArgumentException.class,
                () -> TransformContext.builder().put("key", "two\nlines"));
        assertThrows(IllegalArgumentException.class,
                () -> TransformContext.builder().put("a=b", "value"));
    }
}
