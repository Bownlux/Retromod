/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 ttaute
 */
package com.retromod.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class ResourcePackTransformerTest {
    // Some test cases for resource pack transformation

    // Brick blocks and the item form both have a texture named brick.png
    // prior to the 1.13 format. This checks if the item form is not being renamed
    // to bricks.png like the block form.
    @Test
    void brickItemTextureNotRenamed(@TempDir Path root) throws Exception {
        // demo 1.12.2 pack for this purpose 
        Path brickpack = root.resolve("bricks");
        Path currentItems = brickpack.resolve("assets/minecraft/textures/item");
        Path oldItems = brickpack.resolve("assets/minecraft/textures/items");
        Path currentBlocks = brickpack.resolve("assets/minecraft/textures/block");
        Path oldBlocks = brickpack.resolve("assets/minecraft/textures/blocks");
        Files.createDirectories(currentItems);
        Files.createDirectories(oldItems);
        Files.createDirectories(currentBlocks);
        Files.createDirectories(oldBlocks);
        Files.writeString(brickpack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":3,\"description\":\"Bricks\"}}");
        Files.writeString(oldItems.resolve("brick.png"), "brick item");
        Files.writeString(oldBlocks.resolve("brick.png"), "brick block");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new ResourcePackTransformer("1.21.8").transformPack(brickpack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
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
        }
        
    }
    @Test
    void metadataRenamedProperly(@TempDir Path root) {
        // .png.mcmeta files should not be renamed to .mcmeta or to .png

    }
    @Test
    void collisionTest(@TempDir Path root) {
        // For resource packs which have files on both new and old paths,
        // the file on the new path should be kept.
        }
    }
