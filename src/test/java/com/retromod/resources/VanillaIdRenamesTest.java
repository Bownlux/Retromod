/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.bytes;
import static com.retromod.resources.ResourceFixtures.text;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Minecraft renamed some ids after the Flattening, and a mod's data keeps the old ones. Each
 * rename applies from its release on, and only to data written before it.
 */
class VanillaIdRenamesTest {

    private static final String TAG = "data/forge/tags/block/transparent.json";

    private static String renamed(String entry, String json, String source, String target) {
        return text(VanillaIdRenames.migrate(entry, bytes(json), source, target));
    }

    @Test
    @DisplayName("grass becomes short_grass from 1.20.3, and 1.20.2 keeps it")
    void grassBecomesShortGrass() {
        String tag = "{\"values\":[\"minecraft:grass\",\"minecraft:glass\"]}";

        assertEquals("{\"values\":[\"minecraft:short_grass\",\"minecraft:glass\"]}",
                renamed(TAG, tag, "1.20.1", "1.20.3"));
        assertEquals(tag, renamed(TAG, tag, "1.20.1", "1.20.2"));
        assertEquals(tag, renamed(TAG, tag, "1.20.4", "26.3"), "data written after the rename is current");
    }

    @Test
    @DisplayName("chain becomes iron_chain from 1.21.9, and 1.21.8 keeps it")
    void chainBecomesIronChain() {
        String recipe = "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"C\":\"minecraft:chain\"},"
                + "\"pattern\":[\"C\"],\"result\":{\"id\":\"minecraft:chain_command_block\"}}";

        String out = renamed("data/mod/recipe/x.json", recipe, "1.21.1", "1.21.9");
        assertTrue(out.contains("\"C\":\"minecraft:iron_chain\""), out);
        assertTrue(out.contains("minecraft:chain_command_block"), "only the exact id is renamed");
        assertEquals(recipe, renamed("data/mod/recipe/x.json", recipe, "1.21.1", "1.21.8"));
        assertTrue(renamed("data/mod/recipe/x.json", recipe, "1.21.1", "26.3").contains("minecraft:iron_chain"),
                "the newest host gets it too");
    }

    @Test
    @DisplayName("1.12's grass is the grass block, so 1.12 data keeps it: the table starts at the Flattening")
    void twelveGrassIsTheGrassBlock() {
        String tag = "{\"values\":[\"minecraft:grass\"]}";
        String recipe = "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"##\",\"##\"],\"key\":{\"#\":"
                + "{\"item\":\"minecraft:dirt\"}},\"result\":{\"item\":\"minecraft:grass\",\"count\":4}}";

        assertEquals(tag, renamed(TAG, tag, "1.12.2", "26.3"), "short_grass is a different block");
        assertEquals(recipe, renamed("data/mod/recipe/grass_block.json", recipe, "1.12.2", "1.21.1"));
        assertEquals("setblock ~ ~ ~ minecraft:grass\n", text(VanillaIdRenames.migrateFunction(
                bytes("setblock ~ ~ ~ minecraft:grass\n"), "1.12.2", "26.3")));
        assertEquals("{\"values\":[\"minecraft:short_grass\"]}", renamed(TAG, tag, "1.13", "26.3"),
                "from 1.13 grass is the short plant");
    }

    @Test
    @DisplayName("1.21.2 removed the boat entity types, so an entity tag naming them keeps loading")
    void removedBoatEntitiesBecomeOptional() {
        String entityTag = "data/mod/tags/entity_type/rideable.json";
        String tag = "{\"values\":[\"minecraft:boat\",\"minecraft:chest_boat\",\"minecraft:cow\"]}";

        assertEquals("{\"values\":[{\"id\":\"minecraft:boat\",\"required\":false},{\"id\":\"minecraft:chest_boat\","
                + "\"required\":false},\"minecraft:cow\"]}", renamed(entityTag, tag, "1.21.1", "1.21.2"));
        assertEquals(tag, renamed(entityTag, tag, "1.21.1", "1.21.1"));
        assertTrue(renamed(entityTag, tag, "1.21.1", "26.3").contains("{\"id\":\"minecraft:boat\",\"required\":false}"));
        assertEquals("{\"values\":[\"minecraft:boat\"]}", renamed("data/mod/tags/item/boats.json",
                "{\"values\":[\"minecraft:boat\"]}", "1.21.1", "26.3"), "only the entity left");

        String potion = text(ModDataMigrator.migrate("data/mod/tags/entity_type/projectiles.json",
                bytes("{\"values\":[\"minecraft:potion\"]}"), "1.21.4", "26.3", MigrationContext.Loader.NEOFORGE));
        assertTrue(potion.contains("\"minecraft:splash_potion\", \"minecraft:lingering_potion\""),
                "the thrown potion's split wins over making it optional: " + potion);
    }

    @Test
    @DisplayName("An entry Minecraft removed with no successor becomes optional in its own registry's tag")
    void removedEntriesBecomeOptional() {
        String tag = "{\"values\":[\"minecraft:pottery_shard_prize\",\"minecraft:stick\"]}";

        JsonObject out = JsonParser.parseString(renamed("data/mod/tags/item/shards.json", tag, "1.19.4", "1.20.1"))
                .getAsJsonObject();

        assertEquals("{\"id\":\"minecraft:pottery_shard_prize\",\"required\":false}",
                out.getAsJsonArray("values").get(0).toString());
        assertEquals("minecraft:stick", out.getAsJsonArray("values").get(1).getAsString());
    }

    @Test
    @DisplayName("Function commands name the new id after a block, item or entity argument")
    void functionsAreRenamed() {
        String function = "execute if block ~ ~ ~ minecraft:grass run say hi\n"
                + "summon minecraft:zombie_pigman ~ ~ ~\nsetblock ~ ~ ~ minecraft:chain[axis=y]\n";

        String out = text(VanillaIdRenames.migrateFunction(bytes(function), "1.15.2", "26.3"));

        assertEquals("execute if block ~ ~ ~ minecraft:short_grass run say hi\n"
                + "summon minecraft:zombified_piglin ~ ~ ~\nsetblock ~ ~ ~ minecraft:iron_chain[axis=y]\n", out);
    }

    @Test
    @DisplayName("Renaming twice changes nothing more")
    void idempotent() {
        String tag = "{\"values\":[\"minecraft:grass\",\"minecraft:scute\",\"minecraft:grass_path\"]}";

        String once = renamed(TAG, tag, "1.16.5", "26.3");
        assertEquals(once, renamed(TAG, once, "1.16.5", "26.3"));
        assertEquals("{\"values\":[\"minecraft:short_grass\",\"minecraft:turtle_scute\",\"minecraft:dirt_path\"]}",
                once);
    }

    @Test
    @DisplayName("The table loads, lists each id once per registry, and never maps an id to itself")
    void tableIsWellFormed() {
        Set<String> seen = new HashSet<>();
        for (VanillaIdRenames.Row row : VanillaIdRenames.rows()) {
            assertTrue(seen.add(row.registry() + " " + row.oldId()), "listed twice: " + row);
            assertNotEquals(row.oldId(), row.newId(), row.toString());
        }
        assertTrue(seen.contains("item minecraft:chain"), "the chain row is there");
        assertTrue(seen.contains("enchantment minecraft:sweeping"),
                "1.20.5 renamed it while its old language key stayed");
        assertTrue(seen.contains("entity minecraft:chest_boat"), "1.21.2 kept its language key too");
    }

    @Test
    @DisplayName("sweeping becomes sweeping_edge from 1.20.5 in an enchantment predicate")
    void sweepingBecomesSweepingEdge() {
        String loot = "{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"predicates\":"
                + "{\"minecraft:enchantments\":[{\"enchantment\":\"minecraft:sweeping\"}]}}}";

        assertTrue(renamed("data/mod/predicate/x.json", loot, "1.20.1", "1.20.5")
                .contains("\"minecraft:sweeping_edge\""));
        assertEquals(loot, renamed("data/mod/predicate/x.json", loot, "1.20.1", "1.20.4"));
    }
}
