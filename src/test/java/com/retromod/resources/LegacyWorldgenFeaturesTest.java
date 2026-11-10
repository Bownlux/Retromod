/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Worldgen from 1.21.x mods on 26.1 to 26.3, rewritten as vanilla's own data generator wrote the same change. */
class LegacyWorldgenFeaturesTest {

    private static final String FEATURE = "data/mod/worldgen/configured_feature/thing.json";
    private static final String CARVER = "data/mod/worldgen/configured_carver/thing.json";

    @Test
    @DisplayName("a forest rock becomes a block blob with the tag that holds its old placement check")
    void forestRockBecomesBlockBlob() throws IOException {
        assertJson("""
                {"type":"minecraft:block_blob","config":{"state":{"Name":"minecraft:mossy_cobblestone"},
                 "can_place_on":{"type":"minecraft:matching_block_tag","tag":"minecraft:forest_rock_can_place_on"}}}""",
                convert(FEATURE, """
                {"type":"minecraft:forest_rock","config":{"state":{"Name":"minecraft:mossy_cobblestone"}}}""", "26.3"));
    }

    @Test
    @DisplayName("a dripstone cluster becomes a speleothem cluster that names the dripstone blocks")
    void dripstoneClusterBecomesSpeleothemCluster() throws IOException {
        assertJson("""
                {"type":"minecraft:speleothem_cluster","config":{"chance_of_speleothem_at_max_distance_from_center":0.1,
                 "speleothem_block_layer_thickness":3,"max_distance_from_edge_affecting_chance_of_speleothem":3,
                 "base_block":{"Name":"minecraft:dripstone_block"},
                 "pointed_block":{"Name":"minecraft:pointed_dripstone","Properties":{"thickness":"tip",
                   "vertical_direction":"up","waterlogged":"false"}},
                 "replaceable_blocks":"#minecraft:dripstone_replaceable_blocks"}}""",
                convert(FEATURE, """
                {"type":"minecraft:dripstone_cluster","config":{"chance_of_dripstone_column_at_max_distance_from_center":0.1,
                 "dripstone_block_layer_thickness":3,"max_distance_from_edge_affecting_chance_of_dripstone_column":3}}""",
                        "26.2"));
    }

    @Test
    @DisplayName("a nether cave carver becomes a cave with the bound, thickness and scale the old class hardcoded")
    void netherCaveBecomesCave() throws IOException {
        assertJson("""
                {"type":"minecraft:cave","config":{"probability":0.2,"y":{"absolute":0},"floor_level":-0.7,
                 "horizontal_radius_multiplier":1.0,"vertical_radius_multiplier":1.0,
                 "room_vertical_radius_multiplier":0.5,
                 "count":{"type":"minecraft:very_biased_to_bottom","min_inclusive":0,"max_inclusive":9},
                 "thickness":{"type":"minecraft:trapezoid","min":0.0,"max":6.0,"plateau":2.0},
                 "start_vertical_radius_multiplier":5.0}}""",
                convert(CARVER, """
                {"type":"minecraft:nether_cave","config":{"probability":0.2,"y":{"absolute":0},"floor_level":-0.7,
                 "horizontal_radius_multiplier":1.0,"vertical_radius_multiplier":1.0,"yScale":0.5,
                 "lava_level":{"above_bottom":10},"replaceable":"#minecraft:nether_carver_replaceables"}}""", "26.3"));
    }

    @Test
    @DisplayName("a canyon's vertical scale moves into its shape, and the whole carver reaches 26.3's folder and shape")
    void canyonScaleMovesIntoShape() throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of(CARVER, bytes("""
                {"type":"canyon","config":{"probability":0.01,"y":{"absolute":10},"yScale":3.0,
                 "vertical_rotation":0.0,"shape":{"width_smoothness":3},"lava_level":{"above_bottom":8},
                 "replaceable":"#minecraft:overworld_carver_replaceables"}}""")));
        ModResourceMigrations.standard().run(tree, context("1.21.1", "26.3"));
        assertJson("""
                {"type":"minecraft:canyon","probability":0.01,"y":{"absolute":10},"vertical_rotation":0.0,
                 "shape":{"width_smoothness":3,"y_scale":3.0}}""",
                text(tree, "data/mod/worldgen/carver/thing.json"));
    }

    @Test
    @DisplayName("a key the old feature codec ignored beside the config goes, and a multiface growth gets glow lichen")
    void strayKeyBesideConfigIsDropped() throws IOException {
        assertJson("""
                {"type":"minecraft:multiface_growth","config":{"can_be_placed_on":["minecraft:stone"],
                 "block":"minecraft:glow_lichen"}}""",
                convert(FEATURE, """
                {"type":"minecraft:multiface_growth","block":"lichen","config":{"can_be_placed_on":["minecraft:stone"]}}""",
                        "26.3"));
    }

    @Test
    @DisplayName("a misspelled key the old state codec ignored is dropped, so the state is still read")
    void misspelledStateKeyIsDropped() throws IOException {
        assertJson("""
                {"type":"minecraft:simple_block","config":{"to_place":{"type":"minecraft:simple_state_provider",
                 "state":{"Name":"minecraft:basalt"}}}}""",
                convert(FEATURE, """
                {"type":"minecraft:simple_block","config":{"to_place":{"type":"minecraft:simple_state_provider",
                 "state":{"Name":"minecraft:basalt","Properites":{"axis":"y"}}}}}""", "26.3"));
    }

    @Test
    @DisplayName("a surface rule loses a biome of the mod's own namespace that the jar never ships")
    void surfaceRuleDropsMissingOwnBiome() throws IOException {
        String settings = "data/minecraft/worldgen/noise_settings/overworld.json";
        MapResourceTree tree = new MapResourceTree(Map.of(
                settings, bytes("""
                        {"surface_rule":{"type":"minecraft:biome","biome_is":["mod:comment","mod:real","other:biome"]}}"""),
                "data/mod/worldgen/biome/real.json", bytes("{}")));
        run(tree, context("1.21.1", "26.3"));
        assertJson("""
                {"surface_rule":{"type":"minecraft:biome","biome_is":["mod:real","other:biome"]}}""", text(tree, settings));
    }

    @Test
    @DisplayName("a biome of another mod the jar ships compat data for, or one shipped only in an overlay, is kept")
    void surfaceRuleKeepsCompatAndOverlayBiomes() throws IOException {
        String settings = "data/minecraft/worldgen/noise_settings/overworld.json";
        MapResourceTree tree = new MapResourceTree(Map.of(
                settings, bytes("""
                        {"surface_rule":{"type":"minecraft:biome","biome_is":["mod:gone","mod:in_overlay",
                         "compat:not_here"]}}"""),
                "f41/data/mod/worldgen/biome/in_overlay.json", bytes("{}"),
                "data/compat/worldgen/configured_feature/rock.json", bytes("{}")));
        run(tree, context("1.21.1", "26.3"));
        assertJson("""
                {"surface_rule":{"type":"minecraft:biome","biome_is":["mod:in_overlay","compat:not_here"]}}""",
                text(tree, settings));
    }

    @Test
    @DisplayName("a configured feature keeps its loader conditions on 26.1, and on 26.3 they move onto the unwrapped feature")
    void loaderConditionsStay() throws IOException {
        String conditioned = """
                {"neoforge:conditions":[{"type":"neoforge:mod_loaded","modid":"create"}],"note":"ignored",
                 "type":"minecraft:ore","config":{"size":4,"targets":[{"state":{"Name":"create:zinc_ore"},
                  "target":{"predicate_type":"minecraft:always_true"}}]}}""";
        assertJson("""
                {"neoforge:conditions":[{"type":"neoforge:mod_loaded","modid":"create"}],
                 "type":"minecraft:ore","config":{"size":4,"targets":[{"state":{"Name":"create:zinc_ore"},
                  "target":{"predicate_type":"minecraft:always_true"}}]}}""", convert(FEATURE, conditioned, "26.1"));
        MapResourceTree tree = new MapResourceTree(Map.of(FEATURE, bytes(conditioned)));
        ModResourceMigrations.standard().run(tree, context("1.21.1", "26.3"));
        assertJson("""
                {"neoforge:conditions":[{"type":"neoforge:mod_loaded","modid":"create"}],"type":"minecraft:ore",
                 "size":4,"targets":[{"state":"create:zinc_ore","target":{"predicate_type":"minecraft:always_true"}}]}""",
                text(tree, "data/mod/worldgen/feature/thing.json"));
    }

    @Test
    @DisplayName("the carpets block tag, gone before the mod was built, is kept as the empty tag the mod's version read")
    void staleCarpetsTagIsAddedEmpty() throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of(FEATURE, bytes("""
                {"type":"minecraft:replace_single_block","config":{"cannot_replace":"#minecraft:carpets"}}""")));
        run(tree, context("1.21.1", "26.3"));
        assertJson("{\"values\":[]}", text(tree, "data/minecraft/tags/block/carpets.json"));
    }

    @Test
    @DisplayName("only the changes between the mod's version and the host apply, and data of unknown age is left alone")
    void gatedOnSourceAndTarget() throws IOException {
        String cave = """
                {"type":"minecraft:cave","config":{"probability":0.1,"y":{"absolute":0},"yScale":0.5}}""";
        assertJson(cave, convert(CARVER, cave, "26.2"));
        LegacyWorldgenFeatures converter = new LegacyWorldgenFeatures();
        assertFalse(converter.appliesTo(context(null, "26.3")), "unknown source");
        assertFalse(converter.appliesTo(context("26.3", "26.3")), "mod built for the host");
        assertTrue(converter.appliesTo(context("1.21.1", "26.1")), "1.21.1 mod on 26.1");
    }

    @Test
    @DisplayName("a placed feature naming a vanilla patch 26.1 removed gets that patch back around the renamed feature")
    void vanillaPatchReferenceIsRebuilt() throws IOException {
        assertJson("""
                {"feature":{"type":"minecraft:random_patch","config":{"tries":32,"xz_spread":7,"y_spread":3,
                  "feature":{"feature":"minecraft:grass","placement":[{"type":"minecraft:block_predicate_filter",
                  "predicate":{"type":"minecraft:matching_blocks","blocks":"minecraft:air"}}]}}},
                 "placement":[{"type":"minecraft:count","count":5}]}""",
                convert("data/mod/worldgen/placed_feature/grass.json", """
                {"feature":"minecraft:patch_grass","placement":[{"type":"minecraft:count","count":5}]}""", "26.1"));
    }

    @Test
    @DisplayName("a biome's sugar cane patch and the pumpkin patch right after it swap, as vanilla's own biomes did")
    void pumpkinPatchMovesBeforeSugarCane() throws IOException {
        String biome = "data/mod/worldgen/biome/meadow.json";
        assertJson("""
                {"features":[[],["minecraft:patch_pumpkin","minecraft:patch_sugar_cane","mod:rock"],
                 ["minecraft:patch_sugar_cane","mod:rock","minecraft:patch_pumpkin"]]}""",
                convert(biome, """
                {"features":[[],["minecraft:patch_sugar_cane","minecraft:patch_pumpkin","mod:rock"],
                 ["minecraft:patch_sugar_cane","mod:rock","minecraft:patch_pumpkin"]]}""", "1.21.11"));
        String old = """
                {"features":[["minecraft:patch_sugar_cane","minecraft:patch_pumpkin"]]}""";
        assertJson(old, convert(biome, old, "1.21.10"));
    }

    @Test
    @DisplayName("a mod built for 26.1 still gets the 26.2 speleothem rewrite and the 26.3 carver rebuild")
    void modsBuiltFor26_1KeepLaterRules() throws IOException {
        MapResourceTree cluster = new MapResourceTree(Map.of(FEATURE, bytes("""
                {"type":"minecraft:dripstone_cluster","config":{"dripstone_block_layer_thickness":3}}""")));
        run(cluster, context("26.1", "26.2"));
        assertJson("""
                {"type":"minecraft:speleothem_cluster","config":{"speleothem_block_layer_thickness":3,
                 "base_block":{"Name":"minecraft:dripstone_block"},
                 "pointed_block":{"Name":"minecraft:pointed_dripstone","Properties":{"thickness":"tip",
                   "vertical_direction":"up","waterlogged":"false"}},
                 "replaceable_blocks":"#minecraft:dripstone_replaceable_blocks"}}""", text(cluster, FEATURE));
        MapResourceTree carver = new MapResourceTree(Map.of(CARVER, bytes("""
                {"type":"minecraft:canyon","config":{"probability":0.01,"yScale":3.0,"shape":{"width_smoothness":3}}}""")));
        run(carver, context("26.1", "26.3"));
        assertJson("""
                {"type":"minecraft:canyon","config":{"probability":0.01,"shape":{"width_smoothness":3,"y_scale":3.0}}}""",
                text(carver, CARVER));
    }

    private static String convert(String name, String json, String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of(name, bytes(json)));
        run(tree, context("1.21.1", target));
        return text(tree, name);
    }

    private static void run(MapResourceTree tree, MigrationContext context) throws IOException {
        LegacyWorldgenFeatures converter = new LegacyWorldgenFeatures();
        if (converter.appliesTo(context)) converter.apply(tree, context);
    }

    private static MigrationContext context(String source, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("mod")).build()
                .forRun(List.of("mod", "minecraft"));
    }

    private static String text(MapResourceTree tree, String name) throws IOException {
        return new String(tree.read(name), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertJson(String expected, String actual) {
        JsonElement want = JsonParser.parseString(expected);
        assertEquals(want, JsonParser.parseString(actual), actual);
    }
}
