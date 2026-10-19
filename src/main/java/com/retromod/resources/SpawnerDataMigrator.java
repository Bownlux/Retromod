/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Rewrites mob spawn entries in a mod's data for 26.3. */
final class SpawnerDataMigrator {

    // 26.3 replaced a spawn entry's minCount and maxCount with one required count, and moved a
    // biome's spawners, spawn_costs and creature_spawn_probability into its environment attributes.
    // An old structure spawn override or biome modifier spawn list then failed to parse, and one
    // worldgen file that fails stops the world from loading. An old biome loaded, but nothing spawned.
    static final String FIRST_COUNT_HOST = "26.3";

    // Checked in the 26.2 and 26.3 client jars: 26.3's MobSpawnSettings$SpawnerData reads type and
    // count, no 26.3 class reads minCount, and vanilla's own biomes moved to this attribute with the
    // overlay modifier.
    private static final String NATURAL_SPAWNS = "minecraft:gameplay/natural_mob_spawns";
    private static final String CREATURE_PROBABILITY = "minecraft:gameplay/creature_world_gen_spawn_probability";

    private SpawnerDataMigrator() {
    }

    static boolean appliesTo(String targetMcVersion) {
        return targetMcVersion != null
                && RetromodVersion.compareMcVersions(targetMcVersion, FIRST_COUNT_HOST) >= 0;
    }

    /** Structures, biomes, and the loaders' biome and structure modifiers. */
    static boolean holdsSpawns(String entryName) {
        // NeoForge 26.3's add_spawns biome modifier reads the same entries as vanilla.
        if (!entryName.startsWith("data/") || !entryName.endsWith(".json")) return false;
        return entryName.contains("/worldgen/structure/") || entryName.contains("/worldgen/biome/")
                || entryName.contains("/biome_modifier/") || entryName.contains("/structure_modifier/");
    }

    /** The file rewritten for 26.3, or the same array when nothing was in the old shape. */
    static byte[] migrate(String entryName, byte[] json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        if (!(root instanceof JsonObject object)) return json;
        // Each kind of file keeps its spawn entries in a different place.
        boolean changed;
        if (entryName.contains("/worldgen/biome/")) {
            changed = moveBiomeSpawns(object);
        } else if (entryName.contains("/worldgen/structure/")) {
            changed = object.get("spawn_overrides") instanceof JsonObject overrides && structureSpawns(overrides);
        } else {
            changed = entries(object.get("spawners"));
        }
        return changed ? object.toString().getBytes(StandardCharsets.UTF_8) : json;
    }

    /** Rewrites the spawn list of each category in a structure's spawn overrides. */
    private static boolean structureSpawns(JsonObject overrides) {
        boolean changed = false;
        for (Map.Entry<String, JsonElement> category : overrides.entrySet()) {
            if (category.getValue() instanceof JsonObject override) changed |= entries(override.get("spawns"));
        }
        return changed;
    }

    /** Moves an old biome's top-level spawn settings into its attributes. */
    private static boolean moveBiomeSpawns(JsonObject biome) {
        // First give each entry its count.
        boolean changed = false;
        if (biome.get("spawners") instanceof JsonObject spawners) {
            for (Map.Entry<String, JsonElement> category : spawners.entrySet()) {
                changed |= entries(category.getValue());
            }
        }
        // Then move spawners and spawn_costs into the natural spawns attribute as an overlay. A
        // biome that already sets the attribute is the mod's own 26.3 data and is left alone.
        JsonObject attributes = biome.get("attributes") instanceof JsonObject existing ? existing : null;
        boolean oldSpawns = biome.has("spawners") || biome.has("spawn_costs");
        if (oldSpawns && (attributes == null || !attributes.has(NATURAL_SPAWNS))) {
            JsonObject argument = new JsonObject();
            argument.add("spawn_costs", biome.get("spawn_costs") instanceof JsonObject costs ? costs : new JsonObject());
            argument.add("spawns_by_category",
                    biome.get("spawners") instanceof JsonObject spawners ? spawners : new JsonObject());
            JsonObject spawns = new JsonObject();
            spawns.add("argument", argument);
            spawns.addProperty("modifier", "overlay");
            attributes = attributes != null ? attributes : new JsonObject();
            attributes.add(NATURAL_SPAWNS, spawns);
            biome.remove("spawners");
            biome.remove("spawn_costs");
            biome.add("attributes", attributes);
            changed = true;
        }
        // The creature spawn probability becomes its own attribute the same way.
        if (biome.get("creature_spawn_probability") instanceof JsonPrimitive probability
                && (attributes == null || !attributes.has(CREATURE_PROBABILITY))) {
            attributes = attributes != null ? attributes : new JsonObject();
            attributes.add(CREATURE_PROBABILITY, probability);
            biome.remove("creature_spawn_probability");
            biome.add("attributes", attributes);
            changed = true;
        }
        return changed;
    }

    /** Rewrites one spawn entry, or each entry of a list of them. */
    private static boolean entries(JsonElement element) {
        if (element instanceof JsonArray list) {
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof JsonObject entry) {
                    JsonObject converted = countShapedEntry(entry);
                    if (converted != null) {
                        list.set(i, converted);
                        changed = true;
                    }
                }
            }
            return changed;
        }
        // A single entry is rewritten in place, since its parent still holds it.
        if (element instanceof JsonObject entry) {
            JsonObject converted = countShapedEntry(entry);
            if (converted == null) return false;
            entry.entrySet().clear();
            converted.entrySet().forEach(field -> entry.add(field.getKey(), field.getValue()));
            return true;
        }
        return false;
    }

    /** One old {@code {type, weight, minCount, maxCount}} entry as {@code {type, count, weight}}, or null. */
    static JsonObject countShapedEntry(JsonObject entry) {
        // ForgeDataNamespaceMigrator also calls this for the Forge add_spawns modifiers it activates.
        if (entry.has("count") || !(entry.get("type") instanceof JsonPrimitive)) return null;
        // A range the old game rejected stays as written: each count must be a whole number from 1
        // (the 26.2 codec reads both as positive ints), and the minimum no larger than the maximum.
        Integer min = positiveInt(entry.get("minCount"));
        Integer max = positiveInt(entry.get("maxCount"));
        if (min == null || max == null || min > max) return null;
        // The count is the one number when both are equal, or else a uniform range.
        JsonElement count;
        if (min.equals(max)) {
            count = new JsonPrimitive(min);
        } else {
            JsonObject uniform = new JsonObject();
            uniform.addProperty("type", "minecraft:uniform");
            uniform.addProperty("min_inclusive", min);
            uniform.addProperty("max_inclusive", max);
            count = uniform;
        }
        // Copy the fields in order, with count where minCount was and maxCount dropped.
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : entry.entrySet()) {
            switch (field.getKey()) {
                case "minCount" -> out.add("count", count);
                case "maxCount" -> { }
                default -> out.add(field.getKey(), field.getValue());
            }
        }
        return out;
    }

    /** The value as a whole number of at least 1, or null. */
    private static Integer positiveInt(JsonElement element) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) return null;
        try {
            int value = primitive.getAsBigDecimal().intValueExact();
            return value >= 1 ? value : null;
        } catch (ArithmeticException notWhole) {
            return null;
        }
    }
}
