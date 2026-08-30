/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Converting a pre-1.13 pack moves its textures. A pack that ships its own models still names the
 * old paths, so without a matching rewrite Minecraft resolves nothing and draws the missing-texture
 * checkerboard: the pack looks converted and is quietly broken.
 *
 * <p>The texture tables these tests lean on were contributed by ttaute.
 */
class PackTextureReferencesTest {

    private static Path pack(Path root, int packFormat) throws Exception {
        Path pack = root.resolve("pack");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":" + packFormat + ",\"description\":\"t\"}}");
        return pack;
    }

    private static void write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static String entry(Path zip, String name) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            var e = z.getEntry(name);
            assertNotNull(e, "missing " + name);
            try (var in = z.getInputStream(e)) {
                return new String(in.readAllBytes());
            }
        }
    }

    private static Path transform(Path root, Path pack) throws Exception {
        Path out = root.resolve("out");
        Files.createDirectories(out);
        return new ResourcePackTransformer("1.21.8").transformPack(pack, out);
    }

    @Test
    @DisplayName("A model follows its texture through the directory move and the rename")
    void modelFollowsItsTexture(@TempDir Path root) throws Exception {
        Path pack = pack(root, 3);
        write(pack.resolve("assets/minecraft/textures/blocks/workbench.png"), "png");
        write(pack.resolve("assets/minecraft/models/block/bench.json"),
                "{\"parent\":\"block/cube_all\",\"textures\":{"
                        + "\"all\":\"blocks/workbench\","
                        + "\"side\":\"minecraft:blocks/workbench\","
                        + "\"other\":\"blocks/unchanged\","
                        + "\"ref\":\"#all\"}}");

        String model = entry(transform(root, pack), "assets/minecraft/models/block/bench.json");

        assertTrue(model.contains("\"all\":\"block/crafting_table\""),
                "the rename and the directory move both apply: " + model);
        assertTrue(model.contains("\"side\":\"minecraft:block/crafting_table\""),
                "a namespace must be preserved: " + model);
        assertTrue(model.contains("\"other\":\"block/unchanged\""),
                "a texture with no rename still follows the directory move: " + model);
        assertTrue(model.contains("\"ref\":\"#all\""),
                "a placeholder names another slot, not a texture: " + model);
    }

    @Test
    @DisplayName("A pack's modded namespace is converted too, not just vanilla")
    void moddedNamespaceIsConverted(@TempDir Path root) throws Exception {
        Path pack = pack(root, 3);
        write(pack.resolve("assets/examplemod/textures/blocks/ore.png"), "png");
        write(pack.resolve("assets/examplemod/textures/items/gem.png"), "png");
        write(pack.resolve("assets/examplemod/models/block/ore.json"),
                "{\"textures\":{\"all\":\"examplemod:blocks/ore\"}}");

        Path result = transform(root, pack);
        try (ZipFile zip = new ZipFile(result.toFile())) {
            assertNotNull(zip.getEntry("assets/examplemod/textures/block/ore.png"),
                    "a modded namespace follows the same 1.13 layout change");
            assertNotNull(zip.getEntry("assets/examplemod/textures/item/gem.png"));
            assertNull(zip.getEntry("assets/examplemod/textures/blocks/ore.png"));
        }
        assertTrue(entry(result, "assets/examplemod/models/block/ore.json")
                        .contains("examplemod:block/ore"),
                "its models are repointed as well");
    }

    @Test
    @DisplayName("A pack shipping both layouts keeps the current one instead of being rejected")
    void currentPathWinsOverLegacyCopy(@TempDir Path root) throws Exception {
        Path pack = pack(root, 3);
        write(pack.resolve("assets/minecraft/textures/blocks/stone.png"), "legacy copy");
        write(pack.resolve("assets/minecraft/textures/block/stone.png"), "current copy");

        Path result = transform(root, pack);

        assertEquals("current copy",
                entry(result, "assets/minecraft/textures/block/stone.png"),
                "the file the author maintained on the current path is the one that survives");
        try (ZipFile zip = new ZipFile(result.toFile())) {
            assertNull(zip.getEntry("assets/minecraft/textures/blocks/stone.png"));
        }
    }

    @Test
    @DisplayName("An unreadable model is left exactly as the author wrote it")
    void unreadableModelIsLeftAlone(@TempDir Path root) throws Exception {
        Path pack = pack(root, 3);
        write(pack.resolve("assets/minecraft/textures/blocks/stone.png"), "png");
        write(pack.resolve("assets/minecraft/models/block/broken.json"), "{ not json");

        assertEquals("{ not json",
                entry(transform(root, pack), "assets/minecraft/models/block/broken.json"),
                "a malformed model is Minecraft's to report, not ours to rewrite");
    }

    @Test
    @DisplayName("An animation sidecar stays attached to its texture")
    void animationSidecarFollowsTheTexture(@TempDir Path root) throws Exception {
        Path pack = pack(root, 3);
        write(pack.resolve("assets/minecraft/textures/blocks/workbench.png"), "png");
        write(pack.resolve("assets/minecraft/textures/blocks/workbench.png.mcmeta"),
                "{\"animation\":{}}");

        try (ZipFile zip = new ZipFile(transform(root, pack).toFile())) {
            assertNotNull(zip.getEntry("assets/minecraft/textures/block/crafting_table.png"));
            assertNotNull(zip.getEntry("assets/minecraft/textures/block/crafting_table.png.mcmeta"),
                    "the sidecar keeps its .png.mcmeta name next to the renamed texture");
        }
    }
}
