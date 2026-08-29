/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 ttaute
 */
package com.retromod.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class ResourcePackTransformerTest {
    // Some test cases for resource pack transformation

    @Test
    void renameTest(@TempDir Path root) throws Exception {
        // demo 1.12.2 pack for this purpose 
        // covers basename conflicts (brick.png), metadata stuff,
        // old and new files coexisting
        Path pack = root.resolve("bricks");
        Path currentItems = pack.resolve("assets/minecraft/textures/item");
        Path oldItems = pack.resolve("assets/minecraft/textures/items");
        Path currentBlocks = pack.resolve("assets/minecraft/textures/block");
        Path oldBlocks = pack.resolve("assets/minecraft/textures/blocks");
        Files.createDirectories(currentItems);
        Files.createDirectories(oldItems);
        Files.createDirectories(currentBlocks);
        Files.createDirectories(oldBlocks);
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":3,\"description\":\"Test resource pack\"}}");
        // textures for base name conflicts
        Files.writeString(oldItems.resolve("brick.png"), "brick item");
        Files.writeString(oldBlocks.resolve("brick.png"), "brick block");
        // metadata for file names
        Files.writeString(oldBlocks.resolve("fire_layer_0.png.mcmeta"), "{}");
        // Destination collisions
        Files.writeString(oldBlocks.resolve("fern.png"), "old");
        Files.writeString(currentBlocks.resolve("fern.png"), "new");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new ResourcePackTransformer("1.21.8").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
            // Brick blocks and the item form both have a texture named brick.png
            // prior to the 1.13 format. This checks if the item form is not being renamed
            // to bricks.png like the block form.
            // the item form should be brick.png
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/item/brick.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/item/bricks.png") == null);
            // and the block form bricks.png
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/brick.png") == null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/bricks.png") != null);
            // Metadata files for textures have a name in the format texture.png.mcmeta.
            // The metadata file should keep both file extensions.
            // fire_0.png.mcmeta should not be null
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/fire_0.png.mcmeta") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/fire_0.mcmeta") == null);
        }
    }
}