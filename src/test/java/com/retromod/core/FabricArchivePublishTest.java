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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FabricArchivePublishTest {

    @Test
    @DisplayName("an incomplete pass-through copy cannot replace an existing mod")
    void invalidStagedCopyPreservesExistingOutput(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("invalid.jar"), "not a jar",
                StandardCharsets.UTF_8);
        Path target = directory.resolve("existing.jar");
        writeJar(target);
        byte[] existing = Files.readAllBytes(target);

        assertThrows(IOException.class,
                () -> FabricModTransformer.copyArchiveReplacingAtomically(source, target));
        assertArrayEquals(existing, Files.readAllBytes(target));
        try (var children = Files.list(directory)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".existing.jar.")));
        }
    }

    private static void writeJar(Path target) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
