/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mythic Fantasies, a 1.19.2 Forge mod, on 26.3: its dimension type still used the old flags, the
 * registry load failed on the missing {@code has_ender_dragon_fight}, and no world could start.
 */
class DimensionTypeMigratorTest {

    @Test
    @DisplayName("an old nether-like dimension type gets the matching attributes, sky, and timeline")
    void oldFlagsBecomeAttributes() {
        JsonObject migrated = migrate("""
                {"ultrawarm": true, "natural": false, "piglin_safe": true, "respawn_anchor_works": true,
                 "bed_works": false, "has_raids": false, "has_skylight": false, "has_ceiling": true,
                 "coordinate_scale": 8, "ambient_light": 0.1, "fixed_time": 18000, "logical_height": 128,
                 "effects": "minecraft:the_nether", "infiniburn": "#minecraft:infiniburn_nether", "min_y": 0,
                 "height": 256, "monster_spawn_light_level": 7, "monster_spawn_block_light_limit": 15}""", "26.3");

        assertEquals(JsonParser.parseString("""
                {"coordinate_scale": 8, "ambient_light": 0.1, "has_ceiling": true, "has_skylight": false,
                 "height": 256, "infiniburn": "#minecraft:infiniburn_nether", "logical_height": 128, "min_y": 0,
                 "monster_spawn_block_light_limit": 15, "monster_spawn_light_level": 7,
                 "has_ender_dragon_fight": false, "has_fixed_time": true, "skybox": "none",
                 "cardinal_light": "nether", "timelines": "#minecraft:in_nether",
                 "attributes": {"minecraft:gameplay/bed_rule": {"can_set_spawn": "never", "can_sleep": "never",
                   "destroy_on_use": true}, "minecraft:gameplay/respawn_anchor_works": true,
                   "minecraft:gameplay/can_start_raid": false, "minecraft:gameplay/piglins_zombify": false,
                   "minecraft:gameplay/water_evaporates": true, "minecraft:gameplay/fast_lava": true}}"""), migrated);
    }

    @Test
    @DisplayName("an old overworld-like dimension keeps the overworld day cycle; 26.2 explodes, 26.3 destroys")
    void overworldDimensionAndBedRuleNaming() {
        String old = """
                {"bed_works": false, "has_skylight": true, "has_ceiling": false, "height": 384, "min_y": -64}""";
        JsonObject on262 = migrate(old, "26.2");
        assertEquals("minecraft:overworld", on262.get("default_clock").getAsString());
        assertEquals("#minecraft:in_overworld", on262.get("timelines").getAsString());
        assertTrue(bedRule(on262).has("explodes"), "26.1 and 26.2 call it explodes");
        assertTrue(bedRule(migrate(old, "26.3")).has("destroy_on_use"), "26.3 calls it destroy_on_use");
    }

    @Test
    @DisplayName("a 26.2 dimension type only has its bed rule renamed on 26.3; a current one is untouched")
    void attributeShapeOnlyRenames() {
        String v262 = """
                {"has_ender_dragon_fight": false, "attributes": {"minecraft:gameplay/bed_rule":
                 {"can_set_spawn": "never", "can_sleep": "never", "explodes": true}}}""";
        assertTrue(bedRule(migrate(v262, "26.3")).has("destroy_on_use"));
        byte[] current = """
                {"has_ender_dragon_fight": false, "attributes": {}}""".getBytes(StandardCharsets.UTF_8);
        assertSame(current, DimensionTypeMigrator.migrate(current, "26.3"));
    }

    private static JsonObject migrate(String json, String target) {
        return JsonParser.parseString(new String(DimensionTypeMigrator.migrate(json.getBytes(StandardCharsets.UTF_8),
                target), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject bedRule(JsonObject type) {
        return type.getAsJsonObject("attributes").getAsJsonObject("minecraft:gameplay/bed_rule");
    }
}
