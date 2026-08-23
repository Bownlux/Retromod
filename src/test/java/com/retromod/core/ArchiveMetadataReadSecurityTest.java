/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveMetadataReadSecurityTest {

    private static final int OVERSIZED_METADATA_BYTES = 2 * 1024 * 1024 + 1;

    @Test
    @DisplayName("Fabric transform refuses compressed loader metadata over the text cap")
    void fabricTransformRefusesOversizedMetadata(@TempDir Path tempDir) throws Exception {
        String metadata = "{\"schemaVersion\":1,\"id\":\"large\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"},\"padding\":\""
                + "a".repeat(OVERSIZED_METADATA_BYTES) + "\"}";
        Path source = writeCompressedJar(tempDir.resolve("fabric-large.jar"),
                "fabric.mod.json", metadata);
        assertHighlyCompressed(source, "fabric.mod.json");

        Path output = Files.createDirectory(tempDir.resolve("fabric-output"));
        IOException failure = assertThrows(IOException.class,
                () -> new FabricModTransformer("26.2").transformMod(source, output));

        assertTrue(failure.getMessage().contains("2097152"), failure.getMessage());
        assertFalse(Files.exists(output.resolve("fabric-large-retromod.jar")));
    }

    @Test
    @DisplayName("Forge transform refuses compressed loader metadata over the text cap")
    void forgeTransformRefusesOversizedMetadata(@TempDir Path tempDir) throws Exception {
        String metadata = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                + "[[mods]]\nmodId=\"large\"\n"
                + "[[dependencies.large]]\nmodId=\"minecraft\"\n"
                + "versionRange=\"[1.20.1,)\"\n#"
                + "a".repeat(OVERSIZED_METADATA_BYTES);
        Path source = writeCompressedJar(tempDir.resolve("forge-large.jar"),
                "META-INF/mods.toml", metadata);
        assertHighlyCompressed(source, "META-INF/mods.toml");

        Path output = Files.createDirectory(tempDir.resolve("forge-output"));
        IOException failure = assertThrows(IOException.class,
                () -> new ForgeModTransformer("26.2").transformMod(source, output));

        assertTrue(failure.getMessage().contains("2097152"), failure.getMessage());
        assertFalse(Files.exists(output.resolve("forge-large-retromod.jar")));
    }

    @Test
    @DisplayName("Fabric pre-launch keeps a staged jar whose metadata exceeds the cap")
    void preLaunchKeepsOversizedMetadataForRetry(@TempDir Path tempDir) throws Exception {
        Path input = Files.createDirectory(tempDir.resolve("input"));
        Path processed = tempDir.resolve("processed");
        Path output = tempDir.resolve("mods");
        String metadata = "{\"schemaVersion\":1,\"id\":\"fabric-api\",\"padding\":\""
                + "a".repeat(OVERSIZED_METADATA_BYTES) + "\"}";
        Path source = writeCompressedJar(input.resolve("large-api.jar"),
                "fabric.mod.json", metadata);

        int transformed = new RetromodPreLaunch().transformModsFromFolder(
                input, processed, output, "26.2", true);

        assertEquals(0, transformed);
        assertTrue(Files.exists(source), "the refused source must remain in the input folder");
        assertFalse(Files.exists(processed.resolve(source.getFileName())));
    }

    @Test
    @DisplayName("Explicit transforms refuse symlinked archive inputs")
    void transformsRefuseSymlinkedInputs(@TempDir Path tempDir) throws Exception {
        Path source = writeCompressedJar(tempDir.resolve("source.jar"),
                "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"fixture\","
                        + "\"depends\":{\"minecraft\":\"1.20.1\"}}");
        Path symlink = tempDir.resolve("linked.jar");
        try {
            Files.createSymbolicLink(symlink, source.getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links are not available: " + e.getMessage());
        }
        Path output = Files.createDirectory(tempDir.resolve("output"));

        IOException fabricFailure = assertThrows(IOException.class,
                () -> new FabricModTransformer("26.2").transformMod(symlink, output));
        IOException forgeFailure = assertThrows(IOException.class,
                () -> new ForgeModTransformer("26.2").transformMod(symlink, output));

        assertTrue(fabricFailure.getMessage().contains("not a regular file"));
        assertTrue(forgeFailure.getMessage().contains("not a regular file"));
    }

    @Test
    @DisplayName("Fabric transformed-state checks bound manifest expansion")
    void fabricTransformedStateCheckBoundsManifest(@TempDir Path tempDir) throws Exception {
        Path source = writeCompressedJar(tempDir.resolve("large-manifest.jar"),
                JarFile.MANIFEST_NAME,
                "Manifest-Version: 1.0\r\nRetromod-Transformed: true\r\nX-Fill: "
                        + "a".repeat(OVERSIZED_METADATA_BYTES) + "\r\n\r\n");
        assertHighlyCompressed(source, JarFile.MANIFEST_NAME);

        assertFalse(FabricModTransformer.isAlreadyTransformed(source));
    }

    @Test
    @DisplayName("Forge publication bounds manifest expansion")
    void forgePublicationBoundsManifest(@TempDir Path tempDir) throws Exception {
        String metadata = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                + "[[mods]]\nmodId=\"fixture\"\n"
                + "[[dependencies.fixture]]\nmodId=\"minecraft\"\n"
                + "versionRange=\"[1.20.1,)\"\n";
        Path source = tempDir.resolve("large-manifest-forge.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(source))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry(JarFile.MANIFEST_NAME));
            output.write(("Manifest-Version: 1.0\r\nX-Fill: "
                    + "a".repeat(OVERSIZED_METADATA_BYTES) + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path outputDir = Files.createDirectory(tempDir.resolve("forge-manifest-output"));

        Path result = new ForgeModTransformer("26.2").transformMod(source, outputDir);

        assertNull(result);
        assertFalse(Files.exists(outputDir.resolve("large-manifest-forge-retromod.jar")));
    }

    private static Path writeCompressedJar(Path jar, String entryName, String content)
            throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static void assertHighlyCompressed(Path jar, String entryName) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(entryName);
            assertTrue(entry.getSize() > OVERSIZED_METADATA_BYTES);
            assertTrue(entry.getCompressedSize() < 64 * 1024,
                    "the fixture must exercise a small compressed entry with a large expansion");
        }
    }
}
