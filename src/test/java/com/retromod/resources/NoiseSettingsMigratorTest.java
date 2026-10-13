/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mythic Fantasies, a 1.19.2 Forge mod, on 26.3: its dimension carried 1.19 noise settings inline,
 * the registry load failed on the missing {@code material_rule}, and no world could start.
 */
class NoiseSettingsMigratorTest {

    private static final String DIMENSION = "data/mod/dimension/gourd.json";

    private static final String OLD_DIMENSION = """
            {"type": "mod:gourd", "generator": {"type": "minecraft:noise",
              "biome_source": {"type": "minecraft:fixed", "biome": "mod:gourd"},
              "settings": {"sea_level": 63, "disable_mob_generation": true, "legacy_random_source": false,
                "aquifers_enabled": true, "ore_veins_enabled": true,
                "default_block": {"Name": "minecraft:stone"}, "default_fluid": {"Name": "minecraft:water",
                  "Properties": {"level": "0"}},
                "noise": {"min_y": -64, "height": 384, "size_horizontal": 1, "size_vertical": 2},
                "spawn_target": [{"temperature": [-1, 1], "continentalness": 0.5, "offset": 0}],
                "surface_rule": {"type": "minecraft:block", "result_state": {"Name": "minecraft:grass_block",
                  "Properties": {"snowy": "false"}}},
                "noise_router": {
                  "barrier": {"type": "minecraft:noise", "noise": "minecraft:aquifer_barrier", "xz_scale": 1, "y_scale": 0.5},
                  "fluid_level_floodedness": 0, "fluid_level_spread": 0, "lava": 0,
                  "temperature": {"type": "minecraft:shifted_noise", "noise": "minecraft:temperature", "xz_scale": 0.25,
                    "y_scale": 0, "shift_x": "minecraft:shift_x", "shift_y": 0, "shift_z": "minecraft:shift_z"},
                  "vegetation": 0, "continents": "minecraft:overworld/continents", "erosion": "minecraft:overworld/erosion",
                  "depth": "minecraft:overworld/depth", "ridges": "minecraft:overworld/ridges",
                  "initial_density_without_jaggedness": {"type": "minecraft:mul", "argument1": 4,
                    "argument2": {"type": "minecraft:y_clamped_gradient", "from_y": -64, "to_y": 320,
                      "from_value": 1, "to_value": -1}},
                  "final_density": {"type": "minecraft:interpolated", "argument": {"type": "minecraft:cache_2d",
                    "argument": {"type": "minecraft:invert", "argument": "minecraft:overworld/depth"}}},
                  "vein_toggle": 0, "vein_ridged": 0, "vein_gap": 0}}}}""";

    @Test
    @DisplayName("on 26.3, old inline noise settings become the material rule system")
    void oldSettingsBecomeMaterialSystem() {
        JsonObject settings = migrate(OLD_DIMENSION, "26.3").getAsJsonObject("generator").getAsJsonObject("settings");

        assertEquals(JsonParser.parseString("""
                {"min_y": -64, "height": 384}"""), settings.get("noise"));
        assertEquals("minecraft:stone", settings.get("default_block").getAsString());
        assertEquals(JsonParser.parseString("""
                {"id": "minecraft:water", "properties": {"level": "0"}}"""), settings.get("default_fluid"));
        assertEquals(JsonParser.parseString("""
                {"type": "minecraft:sequence", "sequence": ["minecraft:overworld/copper_ore_vein",
                  "minecraft:overworld/iron_ore_vein", {"type": "minecraft:block",
                  "result_state": {"id": "minecraft:grass_block", "properties": {"snowy": "false"}}}]}"""),
                settings.get("material_rule"), "ore veins run before the old surface rule, as they did");
        assertEquals(JsonParser.parseString("""
                [{"minecraft:overworld/continents": [0.5, 0.5]}]"""), settings.get("spawn_target"),
                "only parameters the router named by id can be keyed by it");

        JsonObject router = settings.getAsJsonObject("noise_router");
        assertEquals(JsonParser.parseString("""
                {"type": "minecraft:interpolated", "cell_size_xz": 4, "cell_size_y": 8,
                 "input": {"type": "minecraft:cache", "input": {"type": "minecraft:reciprocal",
                   "input": "minecraft:overworld/depth"}}}"""), router.get("final_density"));
        assertEquals(JsonParser.parseString("""
                {"type": "minecraft:noise", "noise": "minecraft:temperature", "xz_scale": 0.25, "y_scale": 0,
                 "shift_x": "minecraft:shift_x", "shift_z": "minecraft:shift_z"}"""), router.get("temperature"));
        assertFalse(router.has("barrier"), "aquifer functions moved out of the router");

        JsonObject surface = settings.getAsJsonObject("aquifers").getAsJsonObject("surface_level");
        assertEquals("minecraft:find_top_surface", surface.get("type").getAsString());
        assertEquals(320.0, surface.get("upper_bound").getAsDouble());
        assertEquals(8, surface.get("cell_height").getAsInt());
        assertEquals(JsonParser.parseString("""
                {"type": "minecraft:add", "left": -0.390625, "right": {"type": "minecraft:mul", "left": 4,
                  "right": {"type": "minecraft:gradient", "axis": "y", "from_coordinate": -64, "from_value": 1,
                    "to_coordinate": 320, "to_value": -1}}}"""), surface.get("density"),
                "the surface is where the old density first passed 0.390625");
        assertEquals("minecraft:interpolated", router.getAsJsonObject("chunk_surface_level").get("type").getAsString());
    }

    @Test
    @DisplayName("on 26.1 and 26.2 the old layout stays, with the 1.21.9 surface level")
    void oldLayoutKeepsShapeBefore26_3() {
        JsonObject settings = migrate(OLD_DIMENSION, "26.2").getAsJsonObject("generator").getAsJsonObject("settings");
        JsonObject router = settings.getAsJsonObject("noise_router");

        assertTrue(settings.has("surface_rule"));
        assertFalse(router.has("initial_density_without_jaggedness"));
        assertEquals("minecraft:find_top_surface",
                router.getAsJsonObject("preliminary_surface_level").get("type").getAsString());
        assertEquals("minecraft:cache_2d", router.getAsJsonObject("final_density").getAsJsonObject("argument")
                .get("type").getAsString(), "26.2 still has the old caches");
    }

    @Test
    @DisplayName("26.2 drops weird_scaled_sampler for an interval_select over pre-scaled noises")
    void weirdSamplerBecomesIntervalSelect() {
        JsonElement migrated = NoiseSettingsMigrator.migrate("data/mod/worldgen/density_function/caves.json",
                JsonParser.parseString("""
                        {"type": "minecraft:weird_scaled_sampler", "rarity_value_mapper": "type_2",
                         "noise": "minecraft:spaghetti_2d", "input": "mod:modulator"}"""), "26.2");

        JsonObject select = migrated.getAsJsonObject().getAsJsonObject("argument");
        assertEquals("minecraft:abs", migrated.getAsJsonObject().get("type").getAsString());
        assertEquals(JsonParser.parseString("[-0.75, -0.5, 0.5, 0.75]"), select.get("thresholds"));
        JsonObject first = select.getAsJsonArray("functions").get(0).getAsJsonObject();
        assertEquals(0.5, first.get("argument1").getAsDouble());
        assertEquals(2.0, first.getAsJsonObject("argument2").get("xz_scale").getAsDouble(),
                "the old sampler divided coordinates by the scale");
    }

    @Test
    @DisplayName("settings already in the 26.3 shape, and settings named by id, are left alone")
    void currentDataIsUntouched() {
        byte[] current = """
                {"type": "mod:gourd", "generator": {"type": "minecraft:noise", "settings": "minecraft:overworld",
                  "biome_source": {"type": "minecraft:fixed", "biome": "mod:gourd"}}}"""
                .getBytes(StandardCharsets.UTF_8);
        assertSame(current, NoiseSettingsMigrator.migrate(DIMENSION, current, "26.3"));

        byte[] function = """
                {"type": "minecraft:add", "left": 1, "right": "minecraft:y"}""".getBytes(StandardCharsets.UTF_8);
        assertSame(function, NoiseSettingsMigrator.migrate("data/mod/worldgen/density_function/f.json", function, "26.3"));
    }

    private static JsonObject migrate(String json, String target) {
        byte[] out = NoiseSettingsMigrator.migrate(DIMENSION, json.getBytes(StandardCharsets.UTF_8), target);
        return JsonParser.parseString(new String(out, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
