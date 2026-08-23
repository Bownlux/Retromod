/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModVersionDetectorQuiltTest {

    @Test
    void detectsQuiltOnlyMetadata(@TempDir Path directory) throws Exception {
        Path mod = writeQuiltMod(directory, "\"~1.20.1\"");

        ModVersionInfo info = new ModVersionDetector().detectVersion(mod);

        assertEquals("quilt_fixture", info.modId());
        assertEquals("1.0.0", info.modVersion());
        assertEquals("1.20.1", info.targetMcVersion());
        assertEquals("quilt", info.modLoaderType());
        assertEquals(">=0.20.0", info.modLoaderVersion());
    }

    @Test
    void keepsComplexQuiltMinecraftConstraintsUnknown(@TempDir Path directory) throws Exception {
        Path mod = writeQuiltMod(directory, "{\"all\":[\">=1.20\",\"<1.21\"]}");

        ModVersionInfo info = new ModVersionDetector().detectVersion(mod);

        assertEquals("quilt", info.modLoaderType());
        assertNull(info.targetMcVersion());
    }

    @Test
    void quiltTakesPrecedenceInDualMetadataJars(@TempDir Path directory) throws Exception {
        Path mod = writeQuiltMod(directory, "\"~1.20.1\"", true);

        ModVersionInfo info = new ModVersionDetector().detectVersion(mod);

        assertEquals("quilt", info.modLoaderType());
        assertEquals("quilt_fixture", info.modId());
    }

    @Test
    void rejectsDuplicateNormalizedMetadataEntries(@TempDir Path directory) throws Exception {
        Path mod = writeQuiltMod(directory, "\"~1.20.1\"");
        byte[] original = Files.readAllBytes(mod);
        Path duplicate = directory.resolve("duplicate.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(duplicate))) {
            output.putNextEntry(new JarEntry("quilt.mod.json"));
            output.write(readEntry(original, "quilt.mod.json"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("./quilt.mod.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertThrows(java.io.IOException.class,
                () -> new ModVersionDetector().detectVersion(duplicate));
    }

    @Test
    void rejectsDeepFabricMetadataBeforeGsonWalksIt(@TempDir Path directory) throws Exception {
        String metadata = "{\"schemaVersion\":1,\"id\":\"deep\",\"nested\":"
                + "[".repeat(300) + "0" + "]".repeat(300) + "}";
        Path mod = directory.resolve("deep-fabric.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(mod))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> new ModVersionDetector().detectVersion(mod));

        assertEquals("fabric.mod.json nesting exceeds 256 levels", failure.getMessage());
    }

    @Test
    void neoForgeMetadataTakesPrecedenceOverForgeMetadata(@TempDir Path directory)
            throws Exception {
        String forge = """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                modId="forge_fixture"
                version="1.0"
                [[dependencies.forge_fixture]]
                modId="minecraft"
                versionRange="[1.20.1,)"
                """;
        String neoForge = forge.replace("forge_fixture", "neoforge_fixture");
        Path mod = directory.resolve("dual-toml.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(mod))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write(forge.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            output.write(neoForge.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        ModVersionInfo info = new ModVersionDetector().detectVersion(mod);

        assertEquals("neoforge", info.modLoaderType());
        assertEquals("neoforge_fixture", info.modId());
    }

    private static Path writeQuiltMod(Path directory, String minecraftVersions) throws Exception {
        return writeQuiltMod(directory, minecraftVersions, false);
    }

    private static Path writeQuiltMod(Path directory, String minecraftVersions,
                                      boolean includeFabricMetadata) throws Exception {
        String metadata = """
                {"schema_version":1,"quilt_loader":{
                  "group":"fixture","id":"quilt_fixture","version":"1.0.0",
                  "depends":[
                    {"id":"quilt_loader","versions":">=0.20.0"},
                    {"id":"minecraft","versions":%s}
                  ]
                }}
                """.formatted(minecraftVersions);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry("quilt.mod.json"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (includeFabricMetadata) {
                output.putNextEntry(new JarEntry("fabric.mod.json"));
                output.write(("{\"schemaVersion\":1,\"id\":\"fabric_fixture\","
                        + "\"version\":\"1.0\",\"depends\":{"
                        + "\"minecraft\":\"1.20.1\"}}")
                        .getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        Path jar = directory.resolve("quilt.jar");
        Files.write(jar, bytes.toByteArray());
        return jar;
    }

    private static byte[] readEntry(byte[] jarBytes, String wanted) throws Exception {
        try (var input = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(jarBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (wanted.equals(entry.getName())) return input.readAllBytes();
            }
        }
        throw new AssertionError("Missing entry " + wanted);
    }
}
