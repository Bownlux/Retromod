/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OptiFineMetadataSecurityTest {

    @Test
    @DisplayName("OptiFine version detection refuses an oversized compressed changelog")
    void refusesOversizedCompressedChangelog(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("renderer.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("changelog.txt"));
            output.write(("OptiFine fixture\n" + "a".repeat(2 * 1024 * 1024))
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertEquals("Unknown", OptiFineCompat.getOptiFineVersion(jar));
    }

    @Test
    @DisplayName("OptiFine detection refuses a symlinked jar")
    void refusesSymlinkedJar(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("real-optifine.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new JarEntry("optifine/Installer.class"));
            output.write(1);
            output.closeEntry();
        }
        Path link = tempDir.resolve("OptiFine_link.jar");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("Symbolic links are not available: " + e.getMessage());
        }

        assertFalse(OptiFineCompat.isOptiFine(link));
        assertEquals("Unknown", OptiFineCompat.getOptiFineVersion(link));
    }
}
