/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TerraBlender 1.20.1's placeholder biome writes {@code "carvers": {}}, which Forge 26.2 refused with "Carver:
 * Failed to parse either" and then could not load the biome registry.
 */
class BiomeCarverMigratorTest {

    private static final String BIOME = "data/terrablender/worldgen/biome/deferred_placeholder.json";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{}|[]",
            "{\"air\":[]}|[]",
            "{\"air\":[\"minecraft:cave\",\"minecraft:canyon\"]}|[\"minecraft:cave\",\"minecraft:canyon\"]",
            "{\"air\":\"#minecraft:caves\"}|\"#minecraft:caves\"",
            "{\"air\":\"minecraft:cave\",\"liquid\":[\"mod:lava\"]}|[\"minecraft:cave\",\"mod:lava\"]",
            "{\"air\":\"#mod:caves\",\"liquid\":[\"mod:lava\"]}|\"#mod:caves\""})
    @DisplayName("1.20.1's carvers per step become the one holder set 1.21.2 reads")
    void stepsMerge(String carvers, String expected) {
        assertEquals(JsonParser.parseString(expected), carvers(migrate(BIOME, biome(carvers), "26.2")));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource(delimiter = '|', value = {
            "{}|1.21.1",
            "[\"minecraft:cave\"]|26.2",
            "\"#minecraft:caves\"|26.3",
            "{\"type\":\"minecraft:cave\",\"config\":{}}|26.2"})
    @DisplayName("hosts before 1.21.2 and carvers already in the new shape stay as they are")
    void unchanged(String carvers, String host) {
        byte[] json = biome(carvers);
        assertSame(json, BiomeCarverMigrator.appliesTo(host, BIOME) ? BiomeCarverMigrator.migrate(json) : json);
        assertEquals(JsonParser.parseString(carvers), carvers(migrate(BIOME, json, host)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"data/mod/worldgen/configured_carver/cave.json", "data/mod/worldgen/biome_tags/x.json",
            "assets/mod/worldgen/biome/x.json"})
    @DisplayName("only biome files are read")
    void onlyBiomes(String entry) {
        assertFalse(BiomeCarverMigrator.appliesTo("26.2", entry));
    }

    private static byte[] migrate(String entry, byte[] json, String host) {
        return ModDataMigrator.migrate(entry, json, "1.20.1", host, MigrationContext.Loader.FORGE);
    }

    private static byte[] biome(String carvers) {
        return ("{\"carvers\":" + carvers + ",\"downfall\":0.5,\"features\":[],\"has_precipitation\":false,"
                + "\"temperature\":0.5}").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonElement carvers(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject().get("carvers");
    }
}
