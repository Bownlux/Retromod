/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forge and NeoForge on 1.21.1 load mods built for 1.21 as their own, and NeoForge on 1.21.8 loads
 * mods built for 1.21.7. Jade 15.10.6 for 1.21.1 names only a NeoForge 21.0 floor, which reads as
 * 1.21.0, so in-place loading translated it on every NeoForge 1.21.1 game.
 */
class LoaderAcceptedReleaseTest {

    /** Jade 15.10.6 for NeoForge 1.21.1: no minecraft dependency, only a NeoForge 21.0 floor. */
    private static final String JADE_TOML = """
            modLoader = "javafml"
            loaderVersion = "[1,)"
            license = "CC BY-NC-SA 4.0"
            [[mods]]
            modId = "jade"
            version = "${file.jarVersion}"
            displayName = "Jade"
            [[dependencies.jade]]
            modId = "neoforge"
            type = "required"
            versionRange = "[21.0.143, )"
            ordering = "NONE"
            side = "BOTH"
            """;

    /** Highlighter 1.1.11 for Forge 1.21: a Forge 51 floor and a minecraft floor of 1.21.0. */
    private static final String HIGHLIGHTER_FORGE_TOML = """
            modLoader="javafml"
            loaderVersion="[51,)"
            [[mods]]
            modId="highlighter"
            version="1.1.11"
            [[dependencies.highlighter]]
            \tmodId="forge"
            \tmandatory=true
            \tversionRange="[51,)"
            [[dependencies.highlighter]]
            \tmodId="minecraft"
            \tmandatory=true
            \tversionRange="[1.21.0,)"
            """;

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path directory;

    @BeforeEach
    void noDefaultLoader() {
        ModVersionDetector.setDefaultPreferredLoader(null);
    }

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        ModVersionDetector.setDefaultPreferredLoader(null);
    }

    @Test
    @DisplayName("Jade for 1.21.1 is native on NeoForge 1.21.1, so it is not translated")
    void jadeIsNativeOnNeoForge1211() throws Exception {
        ModVersionInfo jade = detect("1.21.1", "neoforge", jade());
        assertEquals("1.21.1", jade.targetMcVersion());
        assertFalse(jade.needsTransformation("1.21.1"));
    }

    @Test
    @DisplayName("the same jar is still translated on later hosts, which no loader table covers")
    void jadeIsStillTranslatedOnLaterHosts() throws Exception {
        for (String host : new String[] {"1.21.4", "1.21.11", "26.3"}) {
            ModVersionInfo jade = detect(host, "neoforge", jade());
            assertEquals("1.21.0", jade.targetMcVersion(), "host " + host);
            assertTrue(jade.needsTransformation(host), "host " + host);
        }
    }

    @Test
    @DisplayName("the rewrite flow reads the running loader and decides the same way")
    void defaultLoaderGivesTheSameAnswer() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        assertEquals("1.21.1", new ModVersionDetector().detectVersion(jade()).targetMcVersion());
    }

    @Test
    @DisplayName("with no host loader known the version stays as the jar names it")
    void unknownHostLoaderKeepsTheVersion() throws Exception {
        assertEquals("1.21.0", detect("1.21.1", null, jade()).targetMcVersion());
    }

    @Test
    @DisplayName("Forge 1.21.1 runs Forge mods built for 1.21; NeoForge 1.21.1 still needs them translated")
    void forgeModsFollowTheLoaderThatRunsThem() throws Exception {
        Path highlighter = jar("Highlighter-1.21-forge-1.1.11.jar", "META-INF/mods.toml",
                HIGHLIGHTER_FORGE_TOML, null);
        ModVersionInfo onForge = detect("1.21.1", "forge", highlighter);
        assertFalse(onForge.needsTransformation("1.21.1"));
        ModVersionInfo onNeoForge = detect("1.21.1", "neoforge", highlighter);
        assertEquals("1.21.0", onNeoForge.targetMcVersion(),
                "NeoForge never reads mods.toml, so only a translated copy loads there");
        assertTrue(onNeoForge.needsTransformation("1.21.1"));
    }

    @Test
    @DisplayName("NeoForge 1.21.8 runs mods built for 1.21.7; Forge 1.21.8 has no such rule")
    void neoForge1218RunsModsBuiltFor1217() throws Exception {
        String neoToml = JADE_TOML.replace("[21.0.143, )", "[21.7.26-beta,)")
                + "[[dependencies.jade]]\nmodId = \"minecraft\"\ntype = \"required\"\nversionRange = \"[1.21.7,)\"\n";
        Path neo = jar("neo-1.21.7.jar", "META-INF/neoforge.mods.toml", neoToml, "1.0.0");
        assertFalse(detect("1.21.8", "neoforge", neo).needsTransformation("1.21.8"));
        Path forge = jar("forge-1.21.7.jar", "META-INF/mods.toml",
                HIGHLIGHTER_FORGE_TOML.replace("[1.21.0,)", "[1.21.7,)"), null);
        assertTrue(detect("1.21.8", "forge", forge).needsTransformation("1.21.8"));
    }

    private Path jade() throws Exception {
        return jar("Jade-1.21.1-NeoForge-15.10.6.jar", "META-INF/neoforge.mods.toml", JADE_TOML,
                "15.10.6+neoforge");
    }

    private ModVersionInfo detect(String host, String hostLoader, Path jar) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        return ModVersionDetector.metadataOnly().preferring(hostLoader).detectVersion(jar);
    }

    private Path jar(String name, String tomlEntry, String toml, String implementationVersion)
            throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (implementationVersion != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, implementationVersion);
        }
        Path jar = directory.resolve(name);
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.putNextEntry(new JarEntry(tomlEntry));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
