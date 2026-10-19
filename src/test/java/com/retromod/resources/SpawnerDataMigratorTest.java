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
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.bytes;
import static com.retromod.resources.ResourceFixtures.entries;
import static com.retromod.resources.ResourceFixtures.text;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.3 reads a spawn entry's count as one number provider, so an old {@code minCount} and
 * {@code maxCount} entry fails to parse, and one failing worldgen file stops the world.
 */
class SpawnerDataMigratorTest {

    private static final String STRUCTURE = "data/mod/worldgen/structure/nest.json";
    private static final String NEST = "{\"type\":\"minecraft:jigsaw\",\"spawn_overrides\":{\"monster\":"
            + "{\"bounding_box\":\"piece\",\"spawns\":[{\"type\":\"mod:dragon\",\"weight\":1,"
            + "\"minCount\":1,\"maxCount\":3},{\"type\":\"mod:egg\",\"weight\":2,\"minCount\":2,\"maxCount\":2}]}}}";

    private static String migrated(String entry, String json, String target) {
        return text(ModDataMigrator.migrate(entry, bytes(json), "1.20.1", target, MigrationContext.Loader.NEOFORGE));
    }

    @Test
    @DisplayName("A range becomes a uniform count and equal bounds one number, on 26.3 only")
    void spawnOverridesTakeACount() {
        JsonObject structure = JsonParser.parseString(migrated(STRUCTURE, NEST, "26.3")).getAsJsonObject();
        var spawns = structure.getAsJsonObject("spawn_overrides").getAsJsonObject("monster").getAsJsonArray("spawns");

        assertEquals("{\"type\":\"mod:dragon\",\"weight\":1,\"count\":{\"type\":\"minecraft:uniform\","
                + "\"min_inclusive\":1,\"max_inclusive\":3}}", spawns.get(0).toString());
        assertEquals("{\"type\":\"mod:egg\",\"weight\":2,\"count\":2}", spawns.get(1).toString());

        assertEquals(NEST, migrated(STRUCTURE, NEST, "26.2"), "26.2 still reads minCount and maxCount");
    }

    @Test
    @DisplayName("A biome modifier's spawners are rewritten too")
    void biomeModifierSpawns() {
        String modifier = "{\"type\":\"neoforge:add_spawns\",\"biomes\":\"#minecraft:is_overworld\","
                + "\"spawners\":{\"type\":\"mod:dragon\",\"weight\":5,\"minCount\":1,\"maxCount\":1}}";

        assertTrue(migrated("data/mod/neoforge/biome_modifier/dragons.json", modifier, "26.3")
                .contains("\"spawners\":{\"type\":\"mod:dragon\",\"weight\":5,\"count\":1}"));
    }

    @Test
    @DisplayName("An old biome's spawners and costs move into its natural spawn attribute")
    void biomeSpawnsMoveToAttributes() {
        String biome = "{\"temperature\":0.5,\"spawners\":{\"creature\":[{\"type\":\"mod:deer\",\"weight\":8,"
                + "\"minCount\":2,\"maxCount\":4}]},\"spawn_costs\":{},\"creature_spawn_probability\":0.07}";

        JsonObject attributes = JsonParser.parseString(migrated("data/mod/worldgen/biome/glade.json", biome, "26.3"))
                .getAsJsonObject().getAsJsonObject("attributes");

        JsonObject spawns = attributes.getAsJsonObject("minecraft:gameplay/natural_mob_spawns");
        assertEquals("overlay", spawns.get("modifier").getAsString());
        assertEquals(2, spawns.getAsJsonObject("argument").getAsJsonObject("spawns_by_category")
                .getAsJsonArray("creature").get(0).getAsJsonObject().getAsJsonObject("count").get("min_inclusive").getAsInt());
        assertEquals(0.07, attributes.get("minecraft:gameplay/creature_world_gen_spawn_probability").getAsDouble());
    }

    @Test
    @DisplayName("Rewriting twice changes nothing more")
    void idempotent() {
        String once = migrated(STRUCTURE, NEST, "26.3");

        assertEquals(once, migrated(STRUCTURE, once, "26.3"));
    }

    @Test
    @DisplayName("A 1.18.2 structure converted on the way gets the 26.3 count through the standard migration")
    void legacyStructureThroughEveryStep() throws IOException {
        String tower = "{\"type\":\"minecraft:village\",\"config\":{\"start_pool\":\"mod:tower\",\"size\":3},"
                + "\"biomes\":\"#minecraft:is_overworld\",\"adapt_noise\":true,\"spawn_overrides\":{\"monster\":"
                + "{\"bounding_box\":\"piece\",\"spawns\":[{\"type\":\"mod:guard\",\"weight\":1,\"minCount\":1,"
                + "\"maxCount\":2}]}}}";
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/worldgen/configured_structure_feature/tower.json", tower));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.18.2")
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(Set.of("mod")).build();

        ModResourceMigrations.standard().run(tree, context);

        String structure = text(tree.read("data/mod/worldgen/structure/tower.json"));
        assertTrue(structure.contains("\"count\":{\"type\":\"minecraft:uniform\""), structure);
        assertFalse(structure.contains("minCount"), structure);
    }
}
