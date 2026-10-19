/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 ttaute
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

import static com.retromod.resources.ResourceFixtures.*;
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

    /** A pack that names a mod's mixed-case texture from its item model, in a 1.12 language file. */
    private static Path legacyModPack(Path root, int packFormat, String textureFolder) throws Exception {
        Path pack = root.resolve("format" + packFormat);
        writeDirectory(pack, entries(
            "pack.mcmeta", "{\"pack\":{\"pack_format\":" + packFormat + ",\"description\":\"Rubies\"}}",
            "assets/somemod/lang/en_US.lang", "item.ruby.name=Shiny Ruby\n",
            "assets/somemod/models/item/ruby.json",
            "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"somemod:" + textureFolder + "/Ruby\"}}",
            "assets/somemod/textures/" + textureFolder + "/Ruby.png", "ruby"));
        return pack;
    }

    private static Map<String, byte[]> transform(Path pack, String target, Path root) throws Exception {
        Path output = Files.createDirectories(root.resolve("out-" + target));
        return readJar(new ResourcePackTransformer(target).transformPack(pack, output));
    }

    private static String layer0(Map<String, byte[]> pack) {
        JsonObject model = JsonParser.parseString(text(pack.get("assets/somemod/models/item/ruby.json")))
            .getAsJsonObject();
        return model.getAsJsonObject("textures").get("layer0").getAsString();
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("A 1.12 pack gets JSON language files and lowercase ids, as a 1.12 mod does")
    void legacyPackGetsLanguageJsonAndLowercaseIds(String target, @TempDir Path root) throws Exception {
        Path source = legacyModPack(root, 3, "items");
        // The pack is converted in a temporary folder, where a file system that ignores case
        // cannot hold the lowercase copy beside the original.
        boolean caseSensitive = !Files.exists(source.resolve("assets/somemod/textures/items/RUBY.png"));
        Map<String, byte[]> pack = transform(source, target, root);

        JsonObject lang = JsonParser.parseString(text(pack.get("assets/somemod/lang/en_us.json")))
            .getAsJsonObject();
        assertEquals("Shiny Ruby", lang.get("item.ruby.name").getAsString());
        assertEquals("Shiny Ruby", lang.get("item.somemod.ruby").getAsString(),
            "the pack's own item model shows the id");
        assertTrue(pack.containsKey("assets/somemod/lang/en_US.lang"), "the original stays");
        assertEquals("somemod:item/ruby", layer0(pack));
        assertArrayEquals(bytes("ruby"), pack.get("assets/somemod/textures/item/Ruby.png"));
        assertEquals(caseSensitive, pack.containsKey("assets/somemod/textures/item/ruby.png"),
            pack.keySet().toString());
        assertTrue(pack.keySet().stream().noneMatch(name -> name.startsWith("data/")),
            "a resource pack carries no data, so the data steps add nothing: " + pack.keySet());
    }

    @Test
    @DisplayName("A pack built for 1.13 or newer keeps its files, since its own game read them the same way")
    void modernPackKeepsItsLanguageAndIds(@TempDir Path root) throws Exception {
        Map<String, byte[]> pack = transform(legacyModPack(root, 4, "item"), "26.3", root);

        assertFalse(pack.containsKey("assets/somemod/lang/en_us.json"), pack.keySet().toString());
        assertEquals("somemod:item/Ruby", layer0(pack));
        assertFalse(pack.containsKey("assets/somemod/textures/item/ruby.png"));
    }
}
