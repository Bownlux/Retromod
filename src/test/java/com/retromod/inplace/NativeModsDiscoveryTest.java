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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Create Aeronautics set for NeoForge 1.21.1, where every mod is native, reported one translation:
 * Jade 15.10.6, whose NeoForge 21.0 floor read as Minecraft 1.21.0. Forge and NeoForge 1.21.1 load
 * mods built for 1.21 as their own, so in-place loading must leave them alone there.
 */
class NativeModsDiscoveryTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("on NeoForge 1.21.1 only the old mod waits; Jade for 1.21.1 loads as it is")
    void neoForge1211TranslatesOnlyTheOldMod() throws IOException {
        Path mods = tmp.resolve("game").resolve("mods");
        jade(mods.resolve("Jade-1.21.1-NeoForge-15.10.6.jar"));
        Path old = TestModJars.forgeMod(mods.resolve("oldmod-1.20.1.jar"), "oldmod", "1.0.0", "[1.20.1,1.21)");

        DiscoveryEngine.Outcome outcome = discover("neoforge", "1.21.1", "21.1.252",
                ForgeModMetadata.NEOFORGE_TOML, DiscoveryEngine.ServeStyle.FILES);

        assertEquals(List.of(old), outcome.hide());
        assertEquals(List.of(DiscoveryEngine.absolute(old)), sources(outcome));
    }

    @Test
    @DisplayName("on Forge 1.21.1 a Forge mod built for 1.21 loads as it is")
    void forge1211RunsModsBuiltFor121() throws IOException {
        Path mods = tmp.resolve("game").resolve("mods");
        // Highlighter 1.1.11 for Forge 1.21 declares a minecraft floor of 1.21.0.
        TestModJars.jar(mods.resolve("Highlighter-1.21-forge-1.1.11.jar"), null, Map.of("META-INF/mods.toml",
                TestModJars.toml("highlighter", "1.1.11", "[1.21.0,)", "forge", "[51,)", "mandatory=true")));
        Path old = TestModJars.forgeMod(mods.resolve("oldmod-1.20.1.jar"), "oldmod", "1.0.0", "[1.20.1,1.21)");

        DiscoveryEngine.Outcome outcome = discover("forge", "1.21.1", "52.1.21",
                ForgeModMetadata.FORGE_TOML, DiscoveryEngine.ServeStyle.FOLDER);

        assertEquals(List.of(old), outcome.hide());
        assertEquals(List.of(DiscoveryEngine.absolute(old)), sources(outcome));
    }

    @Test
    @DisplayName("on NeoForge 26.3 Jade for 1.21.1 is still translated")
    void neoForge263StillTranslatesJade() throws IOException {
        Path jade = jade(tmp.resolve("game").resolve("mods").resolve("Jade-1.21.1-NeoForge-15.10.6.jar"));

        DiscoveryEngine.Outcome outcome = discover("neoforge", "26.3", "26.3.0.43-beta",
                ForgeModMetadata.NEOFORGE_TOML, DiscoveryEngine.ServeStyle.FILES);

        assertEquals(List.of(DiscoveryEngine.absolute(jade)), sources(outcome));
    }

    /** Jade 15.10.6 for NeoForge 1.21.1, with its version in the manifest and only a NeoForge floor. */
    private static Path jade(Path file) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "15.10.6+neoforge");
        return TestModJars.jar(file, manifest, Map.of("META-INF/neoforge.mods.toml", """
                modLoader = "javafml"
                loaderVersion = "[1,)"
                [[mods]]
                modId = "jade"
                version = "${file.jarVersion}"
                [[dependencies.jade]]
                modId = "neoforge"
                type = "required"
                versionRange = "[21.0.143, )"
                """));
    }

    private DiscoveryEngine.Outcome discover(String loader, String minecraft, String build,
            String hostToml, DiscoveryEngine.ServeStyle style) throws IOException {
        Path game = tmp.resolve("game");
        List<Path> listing;
        try (Stream<Path> files = Files.list(game.resolve("mods"))) {
            listing = files.sorted().toList();
        }
        return DiscoveryEngine.run(new DiscoveryEngine.Request(game, game.resolve("mods"), listing,
                loader, minecraft, build, hostToml, style, new Properties(), null));
    }

    /** The jars waiting for translation, in file name order. */
    private static List<String> sources(DiscoveryEngine.Outcome outcome) throws IOException {
        return InPlaceStatus.read(outcome.statusFile()).pending().stream()
                .map(InPlaceStatus.Source::source).sorted().toList();
    }
}
