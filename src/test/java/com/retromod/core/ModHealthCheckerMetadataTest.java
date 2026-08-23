/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModHealthCheckerMetadataTest {

    @Test
    @DisplayName("health validation recognizes Quilt and NeoForge metadata")
    void recognizesAllSupportedLoaderMetadata(@TempDir Path directory) throws Exception {
        for (String metadata : new String[]{
                "quilt.mod.json", "META-INF/neoforge.mods.toml"}) {
            String safeName = metadata.replace('/', '-');
            Path original = directory.resolve("original-" + safeName + ".jar");
            Path transformed = directory.resolve("transformed-" + safeName + ".jar");
            writeJar(original, metadata);
            writeJar(transformed, metadata);

            assertTrue(ModHealthChecker.validateTransformation(original, transformed),
                "supported metadata was rejected: " + metadata);
        }
    }

    private static void writeJar(Path path, String metadata) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(metadata));
            output.write(new byte[]{1});
            output.closeEntry();
        }
    }
}
