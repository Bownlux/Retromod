/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.1 deleted the {@code random_patch}, {@code flower} and {@code no_bonemeal_flower} features.
 *
 * <p>Bountiful Fares ships flower and crop patches in that shape, and the first one failed the whole
 * worldgen registry load with {@code Unknown registry key in worldgen/feature: minecraft:flower}.
 */
class LegacyRandomPatchMigratorTest {

    private static final String ENTRY =
            "data/bountifulfares/worldgen/configured_feature/chamomile.json";

    private static final String FLOWER_PATCH = """
            {"type": "minecraft:flower", "config": {"tries": 32, "xz_spread": 6, "y_spread": 2,
              "feature": {"feature": {"type": "minecraft:simple_block",
                                      "config": {"to_place": {"type": "minecraft:simple_state_provider",
                                                 "state": {"Name": "minecraft:poppy"}}}},
                          "placement": [{"type": "minecraft:block_predicate_filter",
                                         "predicate": {"type": "minecraft:matching_blocks",
                                                       "blocks": "minecraft:air"}}]}}}""";

    private static JsonObject migrated(String json) {
        byte[] out = ModDataMigrator.migrate(ENTRY, json.getBytes(StandardCharsets.UTF_8), "26.1.2");
        return JsonParser.parseString(new String(out, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    @DisplayName("a flower patch becomes a selector that keeps the spread and the inner placement")
    void flowerPatchKeepsSpreadAndInnerPlacement() {
        JsonObject root = migrated(FLOWER_PATCH);

        assertEquals("minecraft:simple_random_selector", root.get("type").getAsString(),
                "minecraft:flower is not a registered feature on 26.1");
        JsonObject placed = root.getAsJsonObject("config").getAsJsonArray("features")
                .get(0).getAsJsonObject();
        assertEquals("minecraft:simple_block",
                placed.getAsJsonObject("feature").get("type").getAsString(),
                "the inner configured feature is what actually places the flower");

        JsonArray placement = placed.getAsJsonArray("placement");
        JsonObject count = placement.get(0).getAsJsonObject();
        assertEquals("minecraft:count", count.get("type").getAsString());
        assertEquals(32, count.get("count").getAsInt(), "tries becomes the count");
        JsonObject offset = placement.get(1).getAsJsonObject();
        assertEquals("minecraft:random_offset", offset.get("type").getAsString());
        assertEquals(6, offset.getAsJsonObject("xz_spread").get("max").getAsInt());
        assertEquals(-2, offset.getAsJsonObject("y_spread").get("min").getAsInt());
        assertEquals("minecraft:block_predicate_filter",
                placement.get(2).getAsJsonObject().get("type").getAsString(),
                "the inner filter must run at each offset position, after the spread");
    }

    @Test
    @DisplayName("a patch of a placed feature referenced by id keeps that feature's own placement")
    void referencedPlacedFeatureIsWrapped() {
        JsonObject root = migrated("""
                {"type": "minecraft:random_patch",
                 "config": {"feature": "minecraft:patch_grass"}}""");

        JsonObject placed = root.getAsJsonObject("config").getAsJsonArray("features")
                .get(0).getAsJsonObject();
        JsonObject wrapper = placed.getAsJsonObject("feature");
        assertEquals("minecraft:simple_random_selector", wrapper.get("type").getAsString());
        assertEquals("minecraft:patch_grass", wrapper.getAsJsonObject("config")
                .getAsJsonArray("features").get(0).getAsString());
        assertEquals(128, placed.getAsJsonArray("placement").get(0).getAsJsonObject()
                .get("count").getAsInt(), "a missing tries takes the old default of 128");
    }

    @Test
    @DisplayName("a configured feature that is not a patch is returned untouched")
    void leavesOtherFeaturesAlone() {
        String tree = "{\"type\":\"minecraft:tree\",\"config\":{\"flower_decorator\":1}}";
        byte[] in = tree.getBytes(StandardCharsets.UTF_8);
        assertSame(in, ModDataMigrator.migrate(ENTRY, in, "26.1.2"),
                "a file with nothing to migrate must come back as the same array");
    }
}
