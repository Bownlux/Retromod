/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.legacy;

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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMetadataSecurityTest {

    @Test
    @DisplayName("Legacy era detection bounds compressed mcmod.info metadata")
    void boundsOversizedCompressedMetadata(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("legacy.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("mcmod.info"));
            output.write(("[{\"mcversion\":\"1.12.2\",\"padding\":\""
                    + "a".repeat(2 * 1024 * 1024) + "\"}]")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (JarFile archive = new JarFile(jar.toFile())) {
            IOException failure = assertThrows(IOException.class,
                    () -> LegacyVersionSupport.extractVersion(archive, "mcmod.info",
                            "\"mcversion\"\\s*:\\s*\"([^\"]+)\""));
            assertTrue(failure.getMessage().contains("2097152"), failure.getMessage());
        }
    }
}
