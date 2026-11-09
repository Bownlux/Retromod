/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodVersion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Merges a biome's per-step carver lists into the one list 1.21.2 and later read. */
final class BiomeCarverMigrator {

    // 1.21.2 dropped the carving steps: a biome's "carvers" became one holder set instead of a map
    // from "air" and "liquid" to holder sets. Data pack format 57 is where Terralith switches its
    // overlay to the list. An old map, even an empty one, fails the biome with "Carver: Failed to
    // parse either", and one biome that fails stops the world from loading.
    static final String FIRST_SINGLE_LIST_HOST = "1.21.2";

    private static final Set<String> STEPS = Set.of("air", "liquid");

    private BiomeCarverMigrator() {
    }

    static boolean appliesTo(String targetMcVersion, String entryName) {
        return targetMcVersion != null
                && RetromodVersion.compareMcVersions(targetMcVersion, FIRST_SINGLE_LIST_HOST) >= 0
                && ModDataMigrator.dataPath(entryName).startsWith("data/") && entryName.contains("/worldgen/biome/")
                && entryName.endsWith(".json");
    }

    /** The biome with one carver list, or the same array when its carvers were not a map of steps. */
    static byte[] migrate(byte[] json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        if (!(root instanceof JsonObject biome) || !(biome.get("carvers") instanceof JsonObject steps)
                || !STEPS.containsAll(steps.keySet())) {
            return json;
        }
        biome.add("carvers", merged(steps));
        return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(biome)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The steps' holder sets as one: air first, then liquid. */
    private static JsonElement merged(JsonObject steps) {
        List<JsonElement> sets = new ArrayList<>();
        for (String step : List.of("air", "liquid")) {
            JsonElement set = steps.get(step);
            if (set != null && !(set instanceof JsonArray list && list.isEmpty())) sets.add(set);
        }
        if (sets.isEmpty()) return new JsonArray();
        if (sets.size() == 1) return sets.get(0);
        // A tag cannot share a holder set with other entries, so the air step's set stays alone.
        if (sets.stream().anyMatch(BiomeCarverMigrator::isTag)) return sets.get(0);
        JsonArray all = new JsonArray();
        for (JsonElement set : sets) {
            if (set instanceof JsonArray list) all.addAll(list);
            else all.add(set);
        }
        return all;
    }

    private static boolean isTag(JsonElement set) {
        return set.isJsonPrimitive() && set.getAsString().startsWith("#");
    }
}
