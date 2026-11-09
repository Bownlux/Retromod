/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pre-launch decides from a Fabric mod's Minecraft dependency, arrays and escapes included. */
class RetromodPreLaunchFabricMetadataTest {

    @Test
    @DisplayName("an escaped >=26.1- range and a [\"26.1\"] array both pass through on 26.1")
    void nativeEscapedAndArrayDeclarationsPassThrough(@TempDir Path directory) throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Path processed = directory.resolve("processed");
        Path output = directory.resolve("mods");
        // Gradle escapes '>' and '=', as native cloth-config builds ship them.
        Path escaped = writeFabricMod(input.resolve("prelaunch-escaped-native.jar"),
                "\"\\u003e\\u003d26.1-\"");
        Path array = writeFabricMod(input.resolve("prelaunch-array-native.jar"), "[\"26.1\"]");
        byte[] escapedBytes = Files.readAllBytes(escaped);
        byte[] arrayBytes = Files.readAllBytes(array);

        int completed = new RetromodPreLaunch().transformModsFromFolder(
                input, processed, output, "26.1", true);

        assertEquals(2, completed);
        assertArrayEquals(escapedBytes,
                Files.readAllBytes(output.resolve("prelaunch-escaped-native.jar")));
        assertArrayEquals(arrayBytes,
                Files.readAllBytes(output.resolve("prelaunch-array-native.jar")));
        assertFalse(Files.exists(output.resolve("prelaunch-escaped-native-retromod.jar")));
        assertFalse(Files.exists(output.resolve("prelaunch-array-native-retromod.jar")));
        assertFalse(RetromodPreLaunch.getTransformedMods().contains("prelaunch-escaped-native.jar"),
                "a native mod must not ask for a restart as if it were translated");
        assertFalse(RetromodPreLaunch.getTransformedMods().contains("prelaunch-array-native.jar"));
    }

    @Test
    @DisplayName("a [\"1.21\", \"1.21.1\"] mod is translated on 26.1")
    void olderArrayDeclarationIsTranslated(@TempDir Path directory) throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Path processed = directory.resolve("processed");
        Path output = directory.resolve("mods");
        writeFabricMod(input.resolve("prelaunch-array-old.jar"), "[\"1.21\", \"1.21.1\"]");

        int completed = new RetromodPreLaunch().transformModsFromFolder(
                input, processed, output, "26.1", true);

        assertEquals(1, completed);
        assertTrue(Files.isRegularFile(output.resolve("prelaunch-array-old-retromod.jar")));
        assertTrue(RetromodPreLaunch.getTransformedMods().contains("prelaunch-array-old.jar"));
    }

    /** A Fabric mod whose {@code depends.minecraft} is the given raw JSON value. */
    private static Path writeFabricMod(Path jar, String minecraftJson) throws IOException {
        String metadata = "{\"schemaVersion\":1,\"id\":\"prelaunch_metadata_test\","
                + "\"version\":\"1.0.0\",\"depends\":{\"fabricloader\":\"*\","
                + "\"minecraft\":" + minecraftJson + "}}";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
