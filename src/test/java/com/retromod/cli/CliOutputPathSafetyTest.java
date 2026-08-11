/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliOutputPathSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsSameTransformInputAndOutputWithoutChangingSource() throws Exception {
        Path mod = tempDir.resolve("example.jar");
        byte[] original = "not truncated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(mod, original);

        assertThrows(IllegalArgumentException.class,
                () -> RetromodCli.requireDistinctPaths(mod, mod, "transform output"));

        assertArrayEquals(original, Files.readAllBytes(mod));
    }

    @Test
    void rejectsAliasedInputAndOutputWithoutChangingSource() throws Exception {
        Path mod = tempDir.resolve("example.jar");
        byte[] original = "still intact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(mod, original);
        Path alias = tempDir.resolve("alias.jar");
        try {
            Files.createLink(alias, mod);
        } catch (UnsupportedOperationException e) {
            return;
        }

        assertThrows(IllegalArgumentException.class,
                () -> RetromodCli.requireDistinctPaths(mod, alias, "transform output"));

        assertArrayEquals(original, Files.readAllBytes(mod));
    }

    @Test
    void rejectsBatchInputDirectoryAsItsOutputDirectory() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));

        assertThrows(IllegalArgumentException.class,
                () -> RetromodCli.requireDistinctPaths(mods, mods, "batch output directory"));
    }
}
