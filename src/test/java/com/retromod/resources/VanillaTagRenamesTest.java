/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.bytes;
import static com.retromod.resources.ResourceFixtures.text;
import static org.junit.jupiter.api.Assertions.*;

/** 26.1 to 26.3 renamed vanilla tags, and a mod's data keeps naming and adding to the old ones. */
class VanillaTagRenamesTest {

    private static String renamed(String json, String source, String target) {
        return text(VanillaTagRenames.migrate(bytes(json), source, target));
    }

    @Test
    @DisplayName("#concrete_powder becomes #concrete_powders from 26.2, and 26.1.2 keeps it")
    void concretePowderBecomesPlural() {
        String tag = "{\"values\":[\"#minecraft:concrete_powder\",\"minecraft:sand\"]}";

        assertEquals("{\"values\":[\"#minecraft:concrete_powders\",\"minecraft:sand\"]}", renamed(tag, "1.21.1", "26.2"));
        assertEquals(tag, renamed(tag, "1.21.1", "26.1.2"));
        assertEquals(tag, renamed(tag, "26.2", "26.3"), "data written after the rename is current");
    }

    @Test
    @DisplayName("A bare tag is renamed only in a tag field: an ingredient and an exploration map destination")
    void bareTagsInTagFields() {
        String loot = "{\"functions\":[{\"function\":\"minecraft:exploration_map\","
                + "\"destination\":\"minecraft:on_ocean_explorer_maps\"}],\"name\":\"minecraft:on_ocean_explorer_maps\"}";

        String out = renamed(loot, "1.21.1", "26.3");
        assertTrue(out.contains("\"destination\":\"minecraft:on_ocean_monument_maps\""), out);
        assertTrue(out.contains("\"name\":\"minecraft:on_ocean_explorer_maps\""), "a bare id elsewhere is not a tag: " + out);
        assertEquals("{\"tag\":\"minecraft:cauldron_can_remove_dye\"}",
                renamed("{\"tag\":\"minecraft:dyeable\"}", "1.20.1", "26.1.2"));
    }

    @Test
    @DisplayName("Function commands name the new tag")
    void functionCommands() {
        String function = "execute if block ~ ~-1 ~ #minecraft:mushroom_grow_block run say ok\n";

        assertEquals("execute if block ~ ~-1 ~ #minecraft:overrides_mushroom_light_requirement run say ok\n",
                text(VanillaTagRenames.migrateFunction(bytes(function), "1.21.1", "26.3")));
    }

    @Test
    @DisplayName("A mod's addition to a renamed vanilla tag is copied to the new name, and the original stays")
    void tagFilesAreCopied() throws IOException {
        String additions = "{\"replace\":false,\"values\":[\"mymod:loam\"]}";
        MapResourceTree tree = new MapResourceTree(Map.of(
                "data/minecraft/tags/block/convertable_to_mud.json", bytes(additions)));

        assertEquals(1, VanillaTagRenames.copyTagFiles(tree, "1.21.1", "26.3"));
        assertEquals(additions, text(tree.read("data/minecraft/tags/block/convertible_to_mud.json")));
        assertTrue(tree.exists("data/minecraft/tags/block/convertable_to_mud.json"));

        MapResourceTree older = new MapResourceTree(Map.of(
                "data/minecraft/tags/block/convertable_to_mud.json", bytes(additions)));
        assertEquals(0, VanillaTagRenames.copyTagFiles(older, "1.21.1", "26.2"), "26.2 still reads the old tag");
    }

    @Test
    @DisplayName("Items a mod made dyeable get the crafting_dye recipe 26.1 needs, and an optional entry does not")
    void dyeableItemsGetRecipes() throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of("data/minecraft/tags/item/dyeable.json", bytes(
                "{\"values\":[\"mymod:wool_hat\",{\"id\":\"mymod:guess\",\"required\":false},"
                        + "\"#mymod:capes\",\"minecraft:leather_helmet\"]}")));

        VanillaTagRenames.copyTagFiles(tree, "1.21.1", "26.1.2");

        assertTrue(tree.exists("data/minecraft/tags/item/cauldron_can_remove_dye.json"));
        JsonObject recipe = JsonParser.parseString(text(tree.read("data/mymod/recipe/wool_hat_dyed.json"))).getAsJsonObject();
        assertEquals("minecraft:crafting_dye", recipe.get("type").getAsString());
        assertEquals("mymod:wool_hat", recipe.get("target").getAsString());
        assertEquals("mymod:wool_hat", recipe.getAsJsonObject("result").get("id").getAsString());
        assertFalse(tree.exists("data/mymod/recipe/guess_dyed.json"), "an optional entry may name no item");
        assertFalse(tree.exists("data/minecraft/recipe/leather_helmet_dyed.json"), "vanilla ships its own");
    }

    @Test
    @DisplayName("Every old tag path is renamed once, so a reference never needs its registry")
    void oldPathsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (VanillaTagRenames.Rename rename : VanillaTagRenames.RENAMES) {
            assertTrue(seen.add(rename.oldPath()), rename.oldPath() + " is listed twice");
        }
    }
}
