/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTexturePathMappingsTest {

    @TempDir
    Path root;

    @Test
    void movesEveryVerifiedNonBlockTextureAndItsAnimationMetadata() throws Exception {
        Path textures = root.resolve("textures");
        int marker = 0;
        for (String sourceName : LegacyTexturePathMappings.mappings().keySet()) {
            Path source = textures.resolve(sourceName);
            Files.createDirectories(source.getParent());
            Files.write(source, new byte[]{(byte) marker++});
        }
        Path animatedSource = textures.resolve("entity/boat/boat_oak.png");
        Files.writeString(animatedSource.resolveSibling("boat_oak.png.mcmeta"),
            "{\"animation\":{}}");
        Path removedWithoutSuccessor = textures.resolve("entity/llama/llama.png");
        Files.write(removedWithoutSuccessor, new byte[]{100});

        int moved = LegacyTexturePathMappings.apply(textures);

        assertEquals(33, moved);
        assertEquals(33, LegacyTexturePathMappings.mappings().size());
        for (var entry : LegacyTexturePathMappings.mappings().entrySet()) {
            assertFalse(Files.exists(textures.resolve(entry.getKey())), entry.getKey());
            assertTrue(Files.isRegularFile(textures.resolve(entry.getValue())), entry.getValue());
        }
        assertTrue(Files.isRegularFile(
            textures.resolve("entity/boat/oak.png.mcmeta")));
        assertTrue(Files.isRegularFile(removedWithoutSuccessor));
    }

    @Test
    void refusesDestinationCollisionBeforeReplacingEitherFile() throws Exception {
        Path textures = root.resolve("textures");
        Path source = textures.resolve("entity/snowman.png");
        Path destination = textures.resolve("entity/snow_golem.png");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "old");
        Files.writeString(destination, "new");

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            java.io.IOException.class, () -> LegacyTexturePathMappings.apply(textures));

        assertTrue(failure.getMessage().contains("destination already exists"));
        assertEquals("old", Files.readString(source));
        assertEquals("new", Files.readString(destination));
    }
}
