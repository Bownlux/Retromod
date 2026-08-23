/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliMetadataPatchSecurityTest {

    @Test
    void metadataPatchDoesNotFollowTheOldPredictableTempSymlink(@TempDir Path directory)
            throws Exception {
        Path jar = writeFabricJar(directory.resolve("example.jar"));
        Path sentinel = directory.resolve("outside.txt");
        byte[] originalSentinel = "outside stays unchanged".getBytes(StandardCharsets.UTF_8);
        Files.write(sentinel, originalSentinel);

        Path oldPredictableTemp = directory.resolve("example.jar.tmp");
        try {
            Files.createSymbolicLink(oldPredictableTemp, sentinel.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
        }

        RetromodCli.patchModMetadata(jar);

        assertArrayEquals(originalSentinel, Files.readAllBytes(sentinel));
        assertTrue(Files.isSymbolicLink(oldPredictableTemp));
        try (JarFile patched = new JarFile(jar.toFile())) {
            assertTrue(patched.getEntry("fabric.mod.json") != null);
        }
    }

    @Test
    void failedMetadataPatchKeepsTheOriginalAndCleansItsStage(@TempDir Path directory)
            throws Exception {
        Path malformed = directory.resolve("broken.jar");
        byte[] original = "not a jar".getBytes(StandardCharsets.UTF_8);
        Files.write(malformed, original);

        assertThrows(IOException.class, () -> RetromodCli.patchModMetadata(malformed));

        assertArrayEquals(original, Files.readAllBytes(malformed));
        try (var children = Files.list(directory)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".broken.jar.")), "failed patch left a staging file");
        }
    }

    @Test
    void metadataPatchRejectsTraversalAndPreservesTheOriginal(@TempDir Path directory)
            throws Exception {
        Path jar = writeJar(directory.resolve("traversal.jar"),
                new String[]{"fabric.mod.json", "../outside.txt"},
                new byte[][]{fabricMetadata(), new byte[]{1}});

        assertRefusedWithoutPublishing(jar,
                () -> RetromodCli.patchModMetadata(jar, 1_024, 2_048, 10));
    }

    @Test
    void metadataPatchRejectsNormalizedDuplicatesAndPreservesTheOriginal(
            @TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("duplicates.jar"),
                new String[]{"fabric.mod.json", "data/example//value.json",
                        "data/example/value.json"},
                new byte[][]{fabricMetadata(), new byte[]{1}, new byte[]{2}});

        assertRefusedWithoutPublishing(jar,
                () -> RetromodCli.patchModMetadata(jar, 1_024, 2_048, 10));
    }

    @Test
    void metadataPatchRejectsTooManyEntriesAndPreservesTheOriginal(@TempDir Path directory)
            throws Exception {
        Path jar = writeJar(directory.resolve("entry-count.jar"),
                new String[]{"fabric.mod.json", "extra.txt"},
                new byte[][]{fabricMetadata(), new byte[]{1}});

        assertRefusedWithoutPublishing(jar,
                () -> RetromodCli.patchModMetadata(jar, 1_024, 2_048, 1));
    }

    @Test
    void metadataPatchRejectsAggregateExpansionAndPreservesTheOriginal(
            @TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("expanded.jar"),
                new String[]{"one.bin", "two.bin"},
                new byte[][]{new byte[6], new byte[6]});

        assertRefusedWithoutPublishing(jar,
                () -> RetromodCli.patchModMetadata(jar, 10, 10, 10));
    }

    private static void assertRefusedWithoutPublishing(Path jar, ThrowingAction action)
            throws Exception {
        byte[] original = Files.readAllBytes(jar);

        assertThrows(IOException.class, action::run);

        assertArrayEquals(original, Files.readAllBytes(jar));
        String stagePrefix = "." + jar.getFileName() + ".";
        try (var children = Files.list(jar.getParent())) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString()
                    .startsWith(stagePrefix)), "failed patch left a staging file");
        }
    }

    private static Path writeFabricJar(Path path) throws IOException {
        return writeJar(path, new String[]{"fabric.mod.json"},
                new byte[][]{fabricMetadata()});
    }

    private static byte[] fabricMetadata() {
        return ("{\"schemaVersion\":1,\"id\":\"example\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Path writeJar(Path path, String[] names, byte[][] contents)
            throws IOException {
        if (names.length != contents.length) {
            throw new IllegalArgumentException("entry names and contents must have equal lengths");
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (int index = 0; index < names.length; index++) {
                jar.putNextEntry(new JarEntry(names[index]));
                jar.write(contents[index]);
                jar.closeEntry();
            }
        }
        return path;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
