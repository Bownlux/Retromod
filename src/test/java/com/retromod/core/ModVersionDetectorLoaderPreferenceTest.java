/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A jar built for several loaders is read through the metadata of the loader that will run it. */
class ModVersionDetectorLoaderPreferenceTest {

    private static final String FORGE_TOML = """
            modLoader="javafml"
            loaderVersion="[1,)"
            [[mods]]
            modId="multi_fixture"
            version="1.0"
            [[dependencies.multi_fixture]]
            modId="minecraft"
            versionRange="[1.21.1,1.21.2)"
            """;
    private static final String FABRIC_JSON = "{\"schemaVersion\":1,\"id\":\"multi_fixture\",\"version\":\"1.0\","
            + "\"depends\":{\"minecraft\":\"~1.21.1\"}}";

    @AfterEach
    void reset() {
        ModVersionDetector.setDefaultPreferredLoader(null);
    }

    @Test
    @DisplayName("Forge and NeoForge read their own toml in a jar that also carries fabric.mod.json")
    void forgeFamilyReadsItsOwnMetadata(@TempDir Path directory) throws Exception {
        // 34 of the 200 most-downloaded Forge 1.21.1 mods ship one jar for every loader.
        Path jar = multiLoaderJar(directory);

        assertEquals("fabric", new ModVersionDetector().detectVersion(jar).modLoaderType(),
                "with no preference the usual order still reads Fabric first");
        assertEquals("forge", loaderOf(new ModVersionDetector().preferring("forge"), jar));
        assertEquals("neoforge", loaderOf(new ModVersionDetector().preferring("neoforge"), jar));
        assertEquals("fabric", loaderOf(new ModVersionDetector().preferring("fabric"), jar));
    }

    @Test
    @DisplayName("The running loader's default reaches detectors built without a preference")
    void defaultPreferenceReachesEveryDetector(@TempDir Path directory) throws Exception {
        Path jar = multiLoaderJar(directory);
        ModVersionDetector.setDefaultPreferredLoader("NeoForge");

        assertEquals("neoforge", loaderOf(ModVersionDetector.metadataOnly(), jar));
        assertEquals("forge", loaderOf(ModVersionDetector.metadataOnly().preferring("forge"), jar),
                "an explicit preference wins over the default");
    }

    @Test
    @DisplayName("A preferred loader the jar has no metadata for falls back to the usual order")
    void missingPreferredMetadataFallsBack(@TempDir Path directory) throws Exception {
        Path fabricOnly = directory.resolve("fabric-only.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(fabricOnly))) {
            put(out, "fabric.mod.json", FABRIC_JSON);
        }
        assertEquals("fabric", loaderOf(new ModVersionDetector().preferring("forge"), fabricOnly));
        assertEquals("fabric", loaderOf(new ModVersionDetector().preferring("lex"), fabricOnly),
                "an unknown loader name is ignored");
    }

    private static String loaderOf(ModVersionDetector detector, Path jar) throws Exception {
        ModVersionInfo info = detector.detectVersion(jar);
        return info.modLoaderType();
    }

    /** fabric.mod.json, META-INF/mods.toml and META-INF/neoforge.mods.toml in one jar. */
    private static Path multiLoaderJar(Path directory) throws Exception {
        Path jar = directory.resolve("multi-loader.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "fabric.mod.json", FABRIC_JSON);
            put(out, "META-INF/mods.toml", FORGE_TOML);
            put(out, "META-INF/neoforge.mods.toml", FORGE_TOML);
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
