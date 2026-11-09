/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** The manifest's mixin configs go into neoforge.mods.toml once, as plain [[mixins]] entries, for hosts that read only the toml. */
class TomlMixinConfigsTest {

    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        McReflect.setForceNeoForge(savedNeoForge);
    }

    @Test
    @DisplayName("Only NeoForge 1.21.9 and newer, which dropped ModLauncher and its Mixin plugin, need the toml entries")
    void appliesFromNeoForge1219() {
        McReflect.setForceNeoForge(true);
        assertFalse(TomlMixinConfigs.appliesTo("1.21.8"), "Mixin's ModLauncher plugin still reads the manifest");
        assertTrue(TomlMixinConfigs.appliesTo("1.21.9"));
        assertTrue(TomlMixinConfigs.appliesTo("26.3"));
        McReflect.setForceNeoForge(false);
        assertFalse(TomlMixinConfigs.appliesTo("26.3"), "Forge reads the manifest on every version");
    }

    @Test
    @DisplayName("A Forge toml just promoted, or a mod built before 1.21.9, takes the configs; a newer or unknown one does not")
    void listsForOldModsOnly() {
        McReflect.setForceNeoForge(true);
        assertTrue(TomlMixinConfigs.listsFor("26.3", true, null), "a promoted Forge toml");
        assertTrue(TomlMixinConfigs.listsFor("26.3", false, "1.21.1"), "a NeoForge mod whose manifest 1.21.1 read");
        assertFalse(TomlMixinConfigs.listsFor("26.3", false, "1.21.9"), "built for a host that never read the manifest");
        assertFalse(TomlMixinConfigs.listsFor("26.3", false, null), "a version that cannot be read");
    }

    @Test
    @DisplayName("The manifest's names are trimmed, put in plain path form and kept once; other names are left out")
    void readsTheManifestAttribute() throws Exception {
        TomlMixinConfigs.ManifestConfigs configs = TomlMixinConfigs.fromManifest(manifest("MixinConfigs: a.mixins.json,"
                + " ./sub/b.mixins.json , /a.mixins.json, bad\"name.json, ../up.mixins.json"));
        assertEquals(List.of("a.mixins.json", "sub/b.mixins.json"), configs.names());
        assertFalse(configs.gameLibrary());
        assertTrue(TomlMixinConfigs.fromManifest(manifest("FMLModType: GAMELIBRARY\r\nMixinConfigs: mixinextras.init.mixins.json"))
                .gameLibrary(), "a bundled MixinExtras is a game library");
        assertEquals(List.of(), TomlMixinConfigs.fromManifest(null).names());
    }

    @Test
    @DisplayName("Each shipped config the toml does not name gets one [[mixins]] entry")
    void listsMissingConfigsOnce() throws Exception {
        String toml = "modLoader=\"javafml\"\n[[mixins]]\nconfig='./kept.mixins.json'\n";
        TomlMixinConfigs.ManifestConfigs configs = TomlMixinConfigs.fromManifest(
                manifest("MixinConfigs: kept.mixins.json, new.mixins.json"));
        String listed = TomlMixinConfigs.withConfigs(toml, configs, name -> true);
        assertTrue(listed.startsWith(toml), "the original text stays as it was");
        assertTrue(listed.endsWith("[[mixins]]\nconfig = \"new.mixins.json\"\n"), listed);
        assertEquals(1, listed.split("kept.mixins.json", -1).length - 1, "kept.mixins.json is named once: " + listed);
        assertSame(listed, TomlMixinConfigs.withConfigs(listed, configs, name -> true), "a second pass adds nothing");

        String staticArray = "mixins = [{ config = \"a.mixins.json\" }]\n";
        assertSame(staticArray, TomlMixinConfigs.withConfigs(staticArray, configs, name -> true),
                "[[mixins]] tables cannot extend a static array");
    }

    @Test
    @DisplayName("A config the jar does not ship is never listed: FML stops on one another mod lists, or one no jar has")
    void listsOnlyShippedConfigs() throws Exception {
        String toml = "modLoader=\"javafml\"\n";
        // Alex's Mobs names Citadel's config, which only Citadel ships.
        TomlMixinConfigs.ManifestConfigs alexsMobs = TomlMixinConfigs.fromManifest(manifest("MixinConfigs: citadel.mixins.json"));
        assertSame(toml, TomlMixinConfigs.withConfigs(toml, alexsMobs, name -> false));
        TomlMixinConfigs.ManifestConfigs library = TomlMixinConfigs.fromManifest(
                manifest("FMLModType: GAMELIBRARY\r\nMixinConfigs: mixinextras.init.mixins.json"));
        assertSame(toml, TomlMixinConfigs.withConfigs(toml, library, name -> true), "a game library keeps its manifest only");
    }

    private static Manifest manifest(String attributes) throws Exception {
        return new Manifest(new ByteArrayInputStream(("Manifest-Version: 1.0\r\n" + attributes + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8)));
    }
}
