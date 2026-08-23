package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessedDirectorySafetyTest {

    @Test
    void forgeRejectsSymlinkedProcessedDirectory(@TempDir Path root) throws IOException {
        Path input = Files.createDirectories(root.resolve("forge-input"));
        Path outside = Files.createDirectories(root.resolve("forge-outside"));
        Path processed = input.resolve("processed");
        Files.createSymbolicLink(processed, outside);

        IOException failure = assertThrows(IOException.class,
                () -> RetromodForge.prepareProcessedDirectory(processed));

        assertTrue(failure.getMessage().contains("symlink"), failure.getMessage());
    }

    @Test
    void neoForgeRejectsSymlinkedProcessedDirectory(@TempDir Path root) throws IOException {
        Path input = Files.createDirectories(root.resolve("neoforge-input"));
        Path outside = Files.createDirectories(root.resolve("neoforge-outside"));
        Path processed = input.resolve("processed");
        Files.createSymbolicLink(processed, outside);

        IOException failure = assertThrows(IOException.class,
                () -> RetromodNeoForge.prepareProcessedDirectory(processed));

        assertTrue(failure.getMessage().contains("symlink"), failure.getMessage());
    }
}
