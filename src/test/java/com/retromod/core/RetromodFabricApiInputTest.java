/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetromodFabricApiInputTest {

    @Test
    @DisplayName("Issue 186: staged old Fabric API is archived when the host API is loaded")
    void archivesOldFabricApiWithoutCreatingDuplicate(@TempDir Path dir) throws Exception {
        Path input = Files.createDirectories(dir.resolve("retromod-input"));
        Path processed = dir.resolve("processed");
        Path output = dir.resolve("mods");
        Path source = writeFabricJar(input.resolve("fabric-api-old.jar"), """
                {
                  "schemaVersion": 1,
                  "id": "fabric-api",
                  "provides": ["fabric"],
                  "version": "0.116.15+1.21.1",
                  "depends": {"minecraft": ">=1.21- <1.21.2-"}
                }
                """);
        byte[] original = Files.readAllBytes(source);

        int transformed = new RetromodPreLaunch().transformModsFromFolder(
                input, processed, output, "26.1.2", true);

        assertEquals(0, transformed, "archiving an unused API is not a transformed mod");
        assertFalse(Files.exists(source), "the stale top-level API must leave the input folder");
        assertArrayEquals(original, Files.readAllBytes(processed.resolve(source.getFileName())),
                "the original API jar must be preserved unchanged");
        assertFalse(Files.exists(output.resolve(source.getFileName())),
                "Retromod must not install a second Fabric API");
    }

    @Test
    @DisplayName("Issue 186: staged Fabric API stays put until a host API is loaded")
    void retainsOldFabricApiWhenHostApiIsMissing(@TempDir Path dir) throws Exception {
        Path input = Files.createDirectories(dir.resolve("mods/retromod-input"));
        Path processed = dir.resolve("mods/retromod-input/processed");
        Path output = dir.resolve("mods");
        Path source = writeFabricJar(input.resolve("renamed-library.jar"), """
                {
                  "schemaVersion": 1,
                  "id": "fabric",
                  "version": "0.116.15+1.21.1"
                }
                """);

        int transformed = new RetromodPreLaunch().transformModsFromFolder(
                input, processed, output, "26.1.2", false);

        assertEquals(0, transformed);
        assertTrue(Files.exists(source), "the source must remain available for a later retry");
        assertFalse(Files.exists(processed.resolve(source.getFileName())));
        assertFalse(Files.exists(output.resolve(source.getFileName())));
    }

    @Test
    @DisplayName("Fabric API detection uses metadata IDs, not filenames")
    void identifiesApiByMetadata(@TempDir Path dir) throws Exception {
        Path disguisedApi = writeFabricJar(dir.resolve("some-random-name.jar"), """
                {"schemaVersion": 1, "id": "fabric-api"}
                """);
        Path misleadingName = writeFabricJar(dir.resolve("fabric-api-looking-name.jar"), """
                {"schemaVersion": 1, "id": "ordinary-helper", "provides": ["fabric-api"]}
                """);

        assertTrue(RetromodPreLaunch.isFabricApiJar(disguisedApi));
        assertFalse(RetromodPreLaunch.isFabricApiJar(misleadingName));
    }

    @Test
    @DisplayName("Fabric API detection refuses deeply nested metadata")
    void refusesDeepApiMetadata(@TempDir Path dir) throws Exception {
        String nested = "[".repeat(257) + "0" + "]".repeat(257);
        Path deepMetadata = writeFabricJar(dir.resolve("deep-api.jar"),
                "{\"id\":\"fabric-api\",\"padding\":" + nested + "}");

        assertFalse(RetromodPreLaunch.isFabricApiJar(deepMetadata));
    }

    private static Path writeFabricJar(Path jar, String metadata) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
