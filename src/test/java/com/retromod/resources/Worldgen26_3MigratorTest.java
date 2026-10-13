/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.3 rebuilt worldgen data. A 26.2 or older mod's configured features failed to parse there, and
 * a placed feature naming one left the worldgen registry unbound, which stops the server. The
 * shapes below are vanilla 26.2 files and their 26.3 counterparts.
 */
class Worldgen26_3MigratorTest {

    @Test
    @DisplayName("configured features move to worldgen/feature, and carvers to worldgen/carver, from 26.3")
    void foldersMove() {
        assertEquals("data/mod/worldgen/feature/ruby_ore.json",
                Worldgen26_3Migrator.migratedEntryName("data/mod/worldgen/configured_feature/ruby_ore.json", "26.3"));
        assertEquals("data/mod/worldgen/carver/canyon.json",
                Worldgen26_3Migrator.migratedEntryName("data/mod/worldgen/configured_carver/canyon.json", "26.3"));
        assertEquals("data/mod/worldgen/configured_feature/ruby_ore.json",
                Worldgen26_3Migrator.migratedEntryName("data/mod/worldgen/configured_feature/ruby_ore.json", "26.2"));
    }

    @Test
    @DisplayName("an ore loses its config wrapper and writes its block states the 26.3 way")
    void oreIsFlattened() {
        assertJson("""
                {"type": "minecraft:ore", "size": 9, "discard_chance_on_air_exposure": 0.0,
                 "targets": [{"state": "mod:ruby_ore",
                              "target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}},
                             {"state": {"id": "mod:ruby_ore", "properties": {"lit": "true"}},
                              "target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}}]}""",
                """
                {"type": "minecraft:ore", "config": {"size": 9, "discard_chance_on_air_exposure": 0.0,
                 "targets": [{"state": {"Name": "mod:ruby_ore"},
                              "target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}},
                             {"state": {"Name": "mod:ruby_ore", "Properties": {"lit": "true"}},
                              "target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}}]}}""");
    }

    @Test
    @DisplayName("a simple state provider becomes an id object, never a bare string read as a named provider")
    void simpleProviderStaysAnObject() {
        assertJson("""
                {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:sweet_berry_bush", "properties": {"age": "3"}}}""",
                """
                {"type": "minecraft:simple_block", "config": {"to_place": {"type": "minecraft:simple_state_provider",
                 "state": {"Name": "minecraft:sweet_berry_bush", "Properties": {"age": "3"}}}}}""");
        assertJson("""
                {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:poppy"}}""",
                """
                {"type": "minecraft:simple_block", "config": {"to_place": {"type": "minecraft:simple_state_provider",
                 "state": {"Name": "minecraft:poppy"}}}}""");
    }

    @Test
    @DisplayName("old noise keeps its exact output through the legacy normalization")
    void noiseUsesLegacyNormalization() {
        assertJson("""
                {"type": "minecraft:noise", "noise": {"base_octave": -3, "octave_count": 2,
                 "amplitude_modifiers": [1.0, 0.5], "normalize": "legacy"}, "seed": 1, "scale": 1.0, "states": ["minecraft:poppy"]}""",
                """
                {"type": "minecraft:noise_provider", "noise": {"firstOctave": -3, "amplitudes": [1.0, 0.5]},
                 "seed": 1, "scale": 1.0, "states": [{"Name": "minecraft:poppy"}]}""");
    }

    @Test
    @DisplayName("random_offset becomes offset with the horizontal spread on both axes")
    void randomOffsetBecomesOffset() {
        assertJson("""
                {"type": "minecraft:offset", "x": 7, "z": 7, "y": 3}""",
                """
                {"type": "minecraft:random_offset", "xz_spread": 7, "y_spread": 3}""");
    }

    @Test
    @DisplayName("a feature type 26.3 removed becomes no_op, so the registry still binds")
    void removedTypeBecomesNoOp() {
        assertJson("""
                {"type": "minecraft:no_op"}""",
                """
                {"type": "minecraft:kelp", "config": {}}""");
    }

    @Test
    @DisplayName("data already in the 26.3 shape comes back as the same bytes")
    void currentDataIsUntouched() {
        byte[] current = """
                {"type": "minecraft:ore", "size": 9, "targets": [{"state": "minecraft:iron_ore"}]}"""
                .getBytes(StandardCharsets.UTF_8);
        assertSame(current, Worldgen26_3Migrator.migrate(current));
    }

    private static void assertJson(String expected, String old) {
        JsonElement result = Worldgen26_3Migrator.migrate(JsonParser.parseString(old));
        assertEquals(JsonParser.parseString(expected), result, result.toString());
    }
}
