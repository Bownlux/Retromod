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
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class InPlaceSettingsTest {

    @TempDir
    Path game;

    private void writeConfig(String json) throws IOException {
        Path config = game.resolve("config/retromod/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, json);
    }

    private static Properties properties(String value) {
        Properties properties = new Properties();
        if (value != null) properties.setProperty(InPlaceSettings.SYSTEM_PROPERTY, value);
        return properties;
    }

    @Test
    @DisplayName("with no config file, in-place mode is the default")
    void defaultsToInPlaceMode() {
        InPlaceSettings.Mode mode = InPlaceSettings.resolve(game, properties(null));
        assertFalse(mode.writeTranslatedJars());
        assertTrue(mode.inPlace());
        assertEquals(InPlaceSettings.Source.DEFAULT, mode.source());
    }

    @Test
    @DisplayName("an existing config without the key keeps the default, it is never written")
    void existingConfigWithoutTheKeyUsesTheDefault() throws IOException {
        writeConfig("{\"polyfills_enabled\": true}");
        InPlaceSettings.Mode mode = InPlaceSettings.resolve(game, properties(null));
        assertTrue(mode.inPlace());
        assertEquals(InPlaceSettings.Source.DEFAULT, mode.source());
        assertFalse(Files.readString(game.resolve("config/retromod/config.json"))
                .contains(InPlaceSettings.KEY));
    }

    @Test
    @DisplayName("write_translated_jars true in the game directory's config restores rewrite mode")
    void configFileSelectsRewriteMode() throws IOException {
        writeConfig("{\"write_translated_jars\": true}");
        InPlaceSettings.Mode mode = InPlaceSettings.resolve(game, properties(null));
        assertTrue(mode.writeTranslatedJars());
        assertEquals(InPlaceSettings.Source.CONFIG_FILE, mode.source());
    }

    @Test
    @DisplayName("the system property overrides the file for one run")
    void systemPropertyOverridesTheFile() throws IOException {
        writeConfig("{\"write_translated_jars\": true}");
        InPlaceSettings.Mode mode = InPlaceSettings.resolve(game, properties("false"));
        assertTrue(mode.inPlace());
        assertEquals(InPlaceSettings.Source.SYSTEM_PROPERTY, mode.source());

        assertTrue(InPlaceSettings.resolve(game, properties(" TRUE ")).writeTranslatedJars());
    }

    @Test
    @DisplayName("an unreadable property value falls back to the file")
    void invalidPropertyIsIgnored() throws IOException {
        writeConfig("{\"write_translated_jars\": true}");
        InPlaceSettings.Mode mode = InPlaceSettings.resolve(game, properties("yes"));
        assertTrue(mode.writeTranslatedJars());
        assertEquals(InPlaceSettings.Source.CONFIG_FILE, mode.source());
    }

    @Test
    @DisplayName("a non-boolean value in the file is ignored rather than guessed")
    void nonBooleanConfigValueIsIgnored() throws IOException {
        writeConfig("{\"write_translated_jars\": \"true\"}");
        InPlaceSettings.Mode mode = InPlaceSettings.resolve(game, properties(null));
        assertTrue(mode.inPlace());
        assertEquals(InPlaceSettings.Source.DEFAULT, mode.source());
    }

    @Test
    @DisplayName("the default config written for new players states the switch explicitly")
    void defaultConfigCarriesTheSwitch() throws IOException {
        Path example = Path.of("src/main/resources/config.json.example");
        assertTrue(Files.readString(example).contains("\"write_translated_jars\": false"));
    }

    @Test
    @DisplayName("Retromod outputs are recognized by either the runtime or the AOT stamp")
    void retromodOutputsAreRecognized() {
        Manifest runtime = new Manifest();
        runtime.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        runtime.getMainAttributes().putValue("Retromod-Transformed", "true");
        assertTrue(TranslatedJarMarkers.isRetromodOutput(runtime));

        Manifest aot = new Manifest();
        aot.getMainAttributes().putValue("Retromod-AOT-Version", "1.4.0-snapshot.1");
        assertTrue(TranslatedJarMarkers.isRetromodOutput(aot),
                "picker outputs carry only the AOT keys and must not be translated twice");

        Manifest plain = new Manifest();
        plain.getMainAttributes().putValue("Implementation-Title", "Some Mod");
        assertFalse(TranslatedJarMarkers.isRetromodOutput(plain));
        assertFalse(TranslatedJarMarkers.isRetromodOutput(null));
    }

    @Test
    @DisplayName("the switch applies only where the Forge or NeoForge bootstrap loaded Retromod")
    void switchAppliesOnlyWithTheBootstrap() {
        assertFalse(InPlaceSettings.switchApplies(new Properties()),
                "Fabric and Quilt always write translated jars into mods/");
        Properties owner = new Properties();
        owner.setProperty(InPlaceSettings.BOOT_OWNER_PROPERTY, "retromod-1.jar");
        assertTrue(InPlaceSettings.switchApplies(owner));
        Properties mode = new Properties();
        mode.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_REWRITE);
        assertTrue(InPlaceSettings.switchApplies(mode));
    }

    @Test
    @DisplayName("the mode in effect comes from the discovery pass, not the saved setting")
    void modeThisLaunchFollowsTheDiscoveryPass() throws IOException {
        writeConfig("{\"write_translated_jars\": true}");
        Properties inPlace = new Properties();
        inPlace.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        assertEquals(java.util.Optional.of(false),
                InPlaceSettings.writesTranslatedJarsThisLaunch(inPlace),
                "a setting saved during the launch waits for the next one");

        Properties unavailable = new Properties();
        unavailable.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_UNAVAILABLE);
        assertEquals(java.util.Optional.of(false),
                InPlaceSettings.writesTranslatedJarsThisLaunch(unavailable));

        Properties rewrite = new Properties();
        rewrite.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_REWRITE);
        assertEquals(java.util.Optional.of(true),
                InPlaceSettings.writesTranslatedJarsThisLaunch(rewrite));

        assertTrue(InPlaceSettings.writesTranslatedJarsThisLaunch(new Properties()).isEmpty(),
                "no pass ran, so nothing is known");
    }
}
