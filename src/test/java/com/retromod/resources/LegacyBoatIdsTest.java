/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Supplementaries' galleon boats name {@code minecraft:boat}, which 1.21.2 split into one entity per wood. */
class LegacyBoatIdsTest {

    private static final String POOL = "data/supplementaries/worldgen/template_pool/galleon/boats.json";
    private static final String GALLEON_BOAT = "{\"config\":{\"entity\":\"minecraft:boat\","
            + "\"data\":{\"boat_type\":\"dark_oak\",\"loot_table\":\"supplementaries:loot/galleon/cannon\"},"
            + "\"passengers\":[\"minecraft:pillager\"]}}";

    @Test
    @DisplayName("a boat named with its wood beside it becomes that wood's boat on 26.3")
    void boatTakesTheWoodBesideIt() {
        String out = migrate(POOL, GALLEON_BOAT, "1.21.1", "26.3");
        assertTrue(out.contains("\"entity\":\"minecraft:dark_oak_boat\""), out);
        assertTrue(out.contains("\"boat_type\":\"dark_oak\""), "the mod's own data keeps the wood it reads");
    }

    @Test
    @DisplayName("the old entity data's Type, bamboo rafts, chest boats, and a boat without a wood")
    void woodsAndChestBoats() {
        assertTrue(migrate(POOL, "{\"entity\":\"minecraft:chest_boat\",\"Type\":\"minecraft:bamboo\"}", "1.20.1", "26.3")
                .contains("minecraft:bamboo_chest_raft"));
        assertTrue(migrate(POOL, "{\"entity\":\"minecraft:boat\"}", "1.21.1", "1.21.11").contains("minecraft:oak_boat"),
                "the old boat was oak unless its data said otherwise");
    }

    @Test
    @DisplayName("an unknown wood, other files, and hosts before the split are left alone")
    void leftAlone() {
        String unknown = "{\"entity\":\"minecraft:boat\",\"data\":{\"Type\":\"mymod:palm\"}}";
        assertEquals(unknown, migrate(POOL, unknown, "1.21.1", "26.3"));
        String predicate = "{\"condition\":\"minecraft:entity_properties\",\"predicate\":{\"type\":\"minecraft:boat\"}}";
        assertEquals(predicate, migrate("data/mod/loot_table/a.json", predicate, "1.21.1", "26.3"),
                "a predicate matched every boat, so it has no one successor");
        assertEquals(GALLEON_BOAT, migrate(POOL, GALLEON_BOAT, "1.21.1", "1.21.1"));
        assertEquals(GALLEON_BOAT, migrate(POOL, GALLEON_BOAT, "1.21.2", "26.3"), "data written after the split");
    }

    private static String migrate(String name, String json, String source, String target) {
        return new String(LegacyBoatIds.migrate(name, json.getBytes(StandardCharsets.UTF_8), source, target),
                StandardCharsets.UTF_8);
    }
}
