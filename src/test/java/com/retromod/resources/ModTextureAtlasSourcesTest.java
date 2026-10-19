/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModTextureAtlasSourcesTest {

    private static final String BLOCKS = ModTextureAtlasSources.BLOCKS_ATLAS;
    private static final String ITEMS = ModTextureAtlasSources.ITEMS_ATLAS;

    private final ModTextureAtlasSources converter = new ModTextureAtlasSources();

    /** A 1.12 block and item: textures in the pre-1.13 folders, plus one the jar does not ship. */
    private static Map<String, byte[]> legacyMod() {
        return new LinkedHashMap<>(entries(
                "assets/oldmod/blockstates/ruby_ore.json", "{\"variants\":{\"\":{\"model\":\"oldmod:block/ruby_ore\"}}}",
                "assets/oldmod/models/block/ruby_ore.json",
                "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"oldmod:blocks/ruby_ore\"}}",
                "assets/oldmod/models/item/ruby.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oldmod:items/ruby\","
                        + "\"layer1\":\"oldmod:items/not_shipped\",\"layer2\":\"minecraft:items/apple\"}}",
                "assets/oldmod/textures/blocks/ruby_ore.png", "ore-png",
                "assets/oldmod/textures/blocks/ruby_ore.png.mcmeta", "{\"animation\":{}}",
                "assets/oldmod/textures/items/ruby.png", "ruby-png"));
    }

    private static MigrationContext context(String source, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .declaredNamespaces(List.of("oldmod")).build().forRun(List.of("oldmod"));
    }

    private int run(MapResourceTree tree, MigrationContext context) throws IOException {
        return converter.appliesTo(context) ? converter.apply(tree, context) : 0;
    }

    /** The {@code resource} of each single source in one atlas file, in order. */
    private static List<String> singles(MapResourceTree tree, String atlas) throws IOException {
        List<String> resources = new ArrayList<>();
        if (!tree.exists(atlas)) return resources;
        JsonArray sources = JsonParser.parseString(text(tree.read(atlas))).getAsJsonObject().getAsJsonArray("sources");
        for (JsonElement source : sources) {
            if (source.getAsJsonObject().get("type").getAsString().equals("single")) {
                resources.add(source.getAsJsonObject().get("resource").getAsString());
            }
        }
        return resources;
    }

    @Test
    @DisplayName("On 26.3 a 1.12 block texture becomes a blocks atlas sprite and its item texture an items atlas sprite")
    void legacyTexturesOn263() throws IOException {
        Map<String, byte[]> shipped = legacyMod();
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(2, run(tree, context("1.12.2", "26.3")), "blocks.json and items.json written");

        assertEquals(List.of("oldmod:blocks/ruby_ore"), singles(tree, BLOCKS));
        assertEquals(List.of("oldmod:items/ruby"), singles(tree, ITEMS),
                "vanilla's apple and a texture the jar lacks get no source");
        for (Map.Entry<String, byte[]> file : shipped.entrySet()) {
            assertArrayEquals(file.getValue(), tree.read(file.getKey()), file.getKey() + " is unchanged");
        }
    }

    @ParameterizedTest(name = "item-only texture on {0} goes to {1}")
    @CsvSource({"1.21.1, blocks", "1.21.10, blocks", "1.21.11, items", "26.3, items"})
    @DisplayName("Item-only textures join the items atlas from 1.21.11, which split it from the blocks atlas")
    void itemOnlyTextureFollowsTheItemsAtlas(String target, String atlas) throws IOException {
        MapResourceTree tree = new MapResourceTree(legacyMod());

        run(tree, context("1.12.2", target));

        List<String> blocks = singles(tree, BLOCKS);
        List<String> items = singles(tree, ITEMS);
        if (atlas.equals("items")) {
            assertEquals(List.of("oldmod:items/ruby"), items);
            assertFalse(blocks.contains("oldmod:items/ruby"));
        } else {
            assertTrue(blocks.contains("oldmod:items/ruby"));
            assertFalse(tree.exists(ITEMS), "no items atlas before 1.21.11");
        }
    }

    @Test
    @DisplayName("A 1.18.2 texture outside block/ and item/ gets a single source; covered folders do not")
    void otherFoldersOn1182() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/totem.json",
                "{\"textures\":{\"front\":\"oldmod:icons/totem\",\"side\":\"oldmod:block/totem_side\"}}",
                "assets/oldmod/textures/icons/totem.png", "png",
                "assets/oldmod/textures/block/totem_side.png", "png"));

        run(tree, context("1.18.2", "1.21.1"));

        assertEquals(List.of("oldmod:icons/totem"), singles(tree, BLOCKS));
    }

    @Test
    @DisplayName("A texture an OBJ material names is a block texture")
    void materialTextures() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/statue.mtl", "newmtl statue\nmap_Kd oldmod:model/blocks/statue\n",
                "assets/oldmod/textures/model/blocks/statue.png", "png"));

        run(tree, context("1.12.2", "26.3"));

        assertEquals(List.of("oldmod:model/blocks/statue"), singles(tree, BLOCKS));
    }

    @Test
    @DisplayName("An atlas file the mod ships keeps its sources and gains the missing ones once")
    void mergesIntoShippedAtlasAndIsIdempotent() throws IOException {
        Map<String, byte[]> shipped = legacyMod();
        shipped.put(BLOCKS, bytes("{\"sources\":[{\"type\":\"directory\",\"source\":\"oldmod_extra\","
                + "\"prefix\":\"oldmod_extra/\"}]}"));
        MapResourceTree tree = new MapResourceTree(shipped);
        MigrationContext context = context("1.12.2", "1.21.1");

        run(tree, context);
        JsonArray sources = JsonParser.parseString(text(tree.read(BLOCKS))).getAsJsonObject().getAsJsonArray("sources");
        assertEquals("directory", sources.get(0).getAsJsonObject().get("type").getAsString());
        Map<String, String> once = gameResourceDigests(tree.entries());

        assertEquals(0, run(tree, context));
        assertEquals(once, gameResourceDigests(tree.entries()));
    }

    @ParameterizedTest(name = "a {0} mod")
    @ValueSource(strings = {"1.19.3", "1.20.1"})
    @DisplayName("A mod built with atlases already lists its own sprites, so it is left alone")
    void modernModUnchanged(String source) {
        assertFalse(converter.appliesTo(context(source, "26.3")));
        assertFalse(converter.appliesTo(context(null, "26.3")), "an unknown source is not taken as old");
    }

    @Test
    @DisplayName("A host before 1.19.3 still stitches every texture a model names, so it needs no sources")
    void preAtlasHostUnchanged() {
        assertFalse(converter.appliesTo(context("1.12.2", "1.19.2")));
    }
}
