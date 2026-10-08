/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.18.2 jigsaw structures left the 1.19 structure registry unbound (#311). */
class LegacyStructureDataMigratorTest {

    private static final String CAVE_NEST = """
            {
              "type": "minecraft:pillager_outpost",
              "config": { "start_pool": "isleofberk:ss_cave_nest", "size": 2 },
              "max_distance_from_center": 45,
              "biomes": "#isleofberk:has_structure/ss_cave_nest",
              "adapt_noise": true,
              "step": "underground_structures",
              "start_height": { "absolute": 0 },
              "project_start_to_heightmap": "WORLD_SURFACE_WG",
              "use_expansion_hack": false,
              "spawn_overrides": {}
            }
            """;

    @Test
    void writesTheJigsawStructureWhereMinecraft119LooksForIt(@TempDir Path jar)
            throws IOException {
        Path old = jar.resolve(
                "data/isleofberk/worldgen/configured_structure_feature/ss_cave_nest.json");
        Files.createDirectories(old.getParent());
        Files.writeString(old, CAVE_NEST);

        assertEquals(1, LegacyStructureDataMigrator.migrate(jar, "1.18.2", "1.20.1"));

        Path converted = jar.resolve("data/isleofberk/worldgen/structure/ss_cave_nest.json");
        assertTrue(Files.isRegularFile(converted), "the structure set names this file");
        JsonObject structure = JsonParser.parseString(Files.readString(converted))
                .getAsJsonObject();
        assertEquals("minecraft:jigsaw", structure.get("type").getAsString());
        assertEquals("isleofberk:ss_cave_nest", structure.get("start_pool").getAsString(),
                "the pool moves out of the old config object");
        assertEquals(2, structure.get("size").getAsInt());
        assertEquals("beard_thin", structure.get("terrain_adaptation").getAsString(),
                "adapt_noise true becomes the matching terrain adaptation");
        assertEquals("#isleofberk:has_structure/ss_cave_nest",
                structure.get("biomes").getAsString());
        assertFalse(structure.has("config"));
    }

    @Test
    void leavesModsFromMinecraft119AndNonJigsawFeaturesAlone(@TempDir Path jar)
            throws IOException {
        Path old = jar.resolve("data/example/worldgen/configured_structure_feature/a.json");
        Files.createDirectories(old.getParent());
        Files.writeString(old, CAVE_NEST);

        assertEquals(0, LegacyStructureDataMigrator.migrate(jar, "1.19.2", "1.20.1"),
                "a 1.19 mod's data is already in the new format");
        assertNull(LegacyStructureDataMigrator.convert(JsonParser.parseString(
                "{\"type\":\"minecraft:mineshaft\",\"config\":{},\"biomes\":\"#x\"}")
                .getAsJsonObject()), "only jigsaw configs map field for field");
    }

    @Test
    void villageWithoutPlacementFieldsStartsOnTheSurface() {
        JsonObject structure = LegacyStructureDataMigrator.convert(JsonParser.parseString("""
                {"type": "minecraft:village", "biomes": "#example:has_village",
                 "config": {"start_pool": "example:town/start", "size": 6}}
                """).getAsJsonObject());

        assertEquals("WORLD_SURFACE_WG", structure.get("project_start_to_heightmap").getAsString(),
                "1.18.2 placed village-type structures on the surface; y 0 buries them");
        assertTrue(structure.get("use_expansion_hack").getAsBoolean(),
                "villages and outposts used the expansion hack in 1.18.2");
    }

    @Test
    void bastionWithoutPlacementFieldsKeepsItsFixedHeight() {
        JsonObject structure = LegacyStructureDataMigrator.convert(JsonParser.parseString("""
                {"type": "minecraft:bastion_remnant", "biomes": "#example:has_fort",
                 "config": {"start_pool": "example:fort/start", "size": 6}}
                """).getAsJsonObject());

        assertEquals(33, structure.getAsJsonObject("start_height").get("absolute").getAsInt(),
                "1.18.2 started bastion-type structures at y 33");
        assertFalse(structure.has("project_start_to_heightmap"));
        assertFalse(structure.get("use_expansion_hack").getAsBoolean());
    }

    @Test
    void malformedStructureIsSkippedWithoutFailingTheMod(@TempDir Path jar) throws IOException {
        Path old = jar.resolve("data/example/worldgen/configured_structure_feature/bad.json");
        Files.createDirectories(old.getParent());
        Files.writeString(old, """
                {"type": "minecraft:village", "biomes": "#x", "adapt_noise": {},
                 "config": {"start_pool": "example:start"}}
                """);

        assertEquals(0, LegacyStructureDataMigrator.migrate(jar, "1.18.2", "1.20.1"),
                "a wrongly typed value skips that file instead of aborting the mod transform");
    }
}
