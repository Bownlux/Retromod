/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.resources.ResourcePackTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliResourcePackCommandTest {

    @TempDir
    Path root;

    @Test
    void transformsPackWithoutChangingSource() throws Exception {
        Path source = createLegacyPack("old-pack");
        byte[] metadataBefore = Files.readAllBytes(source.resolve("pack.mcmeta"));
        byte[] textureBefore = Files.readAllBytes(source.resolve(
                "assets/minecraft/textures/blocks/workbench.png"));
        Path outputDirectory = root.resolve("output");

        Path result = RetromodCli.transformResourcePack(source, outputDirectory,
                new ResourcePackTransformer("26.2"));

        assertEquals(outputDirectory.resolve("old-pack-retromod.zip"), result);
        assertArrayEquals(metadataBefore, Files.readAllBytes(source.resolve("pack.mcmeta")));
        assertArrayEquals(textureBefore, Files.readAllBytes(source.resolve(
                "assets/minecraft/textures/blocks/workbench.png")));
        try (ZipFile zip = new ZipFile(result.toFile())) {
            assertTrue(zip.getEntry("assets/minecraft/textures/block/crafting_table.png") != null);
            assertTrue(zip.getEntry("assets/minecraft/textures/block/workbench.png") == null);
            assertTrue(zip.getEntry(
                    "assets/minecraft/textures/block/crafting_table.png.mcmeta") != null);
            assertTrue(zip.getEntry("assets/minecraft/textures/item/workbench.png") != null);
            String metadata = new String(zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes());
            assertTrue(metadata.contains("\"min_format\""));
            assertTrue(metadata.contains("88"));
        }
    }

    @Test
    void refusesToReplaceExistingOutput() throws Exception {
        Path source = createLegacyPack("collision");
        Path outputDirectory = root.resolve("output");
        Files.createDirectories(outputDirectory);
        Path existing = outputDirectory.resolve("collision-retromod.zip");
        Files.writeString(existing, "keep");

        IOException failure = assertThrows(IOException.class,
                () -> RetromodCli.transformResourcePack(source, outputDirectory,
                        new ResourcePackTransformer("26.2")));

        assertTrue(failure.getMessage().contains("output already exists"));
        assertEquals("keep", Files.readString(existing));
    }

    @Test
    void refusesOutputInsideDirectorySource() throws Exception {
        Path source = createLegacyPack("nested-output");

        IOException failure = assertThrows(IOException.class,
                () -> RetromodCli.transformResourcePack(source, source.resolve("output"),
                        new ResourcePackTransformer("26.2")));

        assertTrue(failure.getMessage().contains("cannot be inside the source pack"));
    }

    private Path createLegacyPack(String name) throws IOException {
        Path pack = root.resolve(name);
        Path texture = pack.resolve("assets/minecraft/textures/blocks/workbench.png");
        Files.createDirectories(texture.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":3,\"description\":\"old\"}}");
        Files.write(texture, new byte[]{1, 2, 3});
        Files.writeString(texture.resolveSibling("workbench.png.mcmeta"),
                "{\"animation\":{}}");
        Path matchingItem = pack.resolve("assets/minecraft/textures/items/workbench.png");
        Files.createDirectories(matchingItem.getParent());
        Files.write(matchingItem, new byte[]{4, 5, 6});
        return pack;
    }
}
