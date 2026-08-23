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
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetromodPreLaunchQuiltMetadataTest {

    @Test
    @DisplayName("real Quilt metadata reaches the native-version pass-through decision")
    void realQuiltDependencyShapePassesThrough(@TempDir Path directory) throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Path processed = directory.resolve("processed");
        Path output = directory.resolve("mods");
        Path source = input.resolve("native-quilt.jar");
        writeJar(source, metadata("26.1"));
        byte[] original = Files.readAllBytes(source);

        int completed = new RetromodPreLaunch().transformModsFromFolder(
            input, processed, output, "26.1", true);

        assertEquals(1, completed);
        assertArrayEquals(original, Files.readAllBytes(output.resolve("native-quilt.jar")));
        assertFalse(Files.exists(output.resolve("native-quilt-retromod.jar")));
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(processed.resolve("native-quilt.jar")));
    }

    @Test
    @DisplayName("old Quilt mods use the Quilt wrapper and receive target metadata")
    void oldQuiltMetadataIsTransformed(@TempDir Path directory) throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Path processed = directory.resolve("processed");
        Path output = directory.resolve("mods");
        Path source = input.resolve("old-quilt.jar");
        writeJar(source, metadata("1.20.1"));

        int completed = new RetromodPreLaunch().transformModsFromFolder(
            input, processed, output, "26.1", true);

        Path transformed = output.resolve("old-quilt-retromod.jar");
        assertEquals(1, completed);
        assertTrue(Files.isRegularFile(transformed));
        assertEquals("=26.1", readMinecraftVersion(transformed));
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(processed.resolve("old-quilt.jar")));
    }

    @Test
    @DisplayName("Quilt metadata controls mixed-metadata pre-launch decisions")
    void quiltVersionTakesPrecedenceOverNativeFabricVersion(@TempDir Path directory)
            throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Path processed = directory.resolve("processed");
        Path output = directory.resolve("mods");
        Path source = input.resolve("dual-authority-quilt.jar");
        writeDualMetadataJar(source, "1.20.1", "26.1");

        int completed = new RetromodPreLaunch().transformModsFromFolder(
            input, processed, output, "26.1", true);

        assertEquals(1, completed);
        assertTrue(Files.isRegularFile(output.resolve("dual-authority-quilt-retromod.jar")));
        assertTrue(RetromodPreLaunch.getTransformedMods().contains(
            "dual-authority-quilt.jar"));
    }

    @Test
    @DisplayName("Quilt opt-out jars pass through without metadata or name changes")
    void quiltOptOutPassesThrough(@TempDir Path directory) throws Exception {
        Path input = Files.createDirectory(directory.resolve("input"));
        Path processed = directory.resolve("processed");
        Path output = directory.resolve("mods");
        Path source = input.resolve("opted-out-quilt.jar");
        writeJar(source, metadata("1.20.1"), true);
        byte[] original = Files.readAllBytes(source);

        int completed = new RetromodPreLaunch().transformModsFromFolder(
            input, processed, output, "26.1", true);

        assertEquals(1, completed);
        assertArrayEquals(original, Files.readAllBytes(output.resolve("opted-out-quilt.jar")));
        assertFalse(Files.exists(output.resolve("opted-out-quilt-retromod.jar")));
        assertEquals("1.20.1", readMinecraftVersion(output.resolve("opted-out-quilt.jar")));
    }

    private static void writeJar(Path path, String metadata) throws IOException {
        writeJar(path, metadata, false);
    }

    private static void writeJar(Path path, String metadata, boolean optedOut)
            throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("quilt.mod.json"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (optedOut) {
                output.putNextEntry(new JarEntry("META-INF/retromod-opt-out"));
                output.closeEntry();
            }
        }
    }

    private static void writeDualMetadataJar(Path path, String quiltMinecraft,
            String fabricMinecraft) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("quilt.mod.json"));
            output.write(metadata(quiltMinecraft).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(("{\"schemaVersion\":1,\"id\":\"fabric_alias\","
                + "\"version\":\"1.0\",\"depends\":{\"minecraft\":\""
                + fabricMinecraft + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String readMinecraftVersion(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry metadata = jar.getJarEntry("quilt.mod.json");
            try (var input = jar.getInputStream(metadata)) {
                return QuiltMetadataCompat.readMinecraftVersion(input);
            }
        }
    }

    private static String metadata(String minecraftVersion) {
        return """
            {
              "schema_version": 1,
              "quilt_loader": {
                "group": "retromod.example",
                "id": "example",
                "depends": [
                  {"id": "quilt_loader", "versions": ">=0.20.0"},
                  {"id": "minecraft", "versions": "%s"}
                ]
              }
            }
            """.formatted(minecraftVersion);
    }
}
