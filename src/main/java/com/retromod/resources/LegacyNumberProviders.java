/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.util.JsonSecurity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Rewrites loot number providers for 26.3, which split them into whole-number and fraction kinds. */
final class LegacyNumberProviders implements ModResourceConverter {

    /** The first host with separate int and float providers: 26.2's jar has no float provider types. */
    static final String FIRST_SPLIT_PROVIDER_HOST = "26.3";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 256;

    private enum Kind { INT, FLOAT }

    // The loot fields 26.3 reads as a fraction or a whole number, read from its function, condition
    // and pool codecs. Old mods name functions in "function" and conditions in "condition".
    private static final Map<String, Map<String, Kind>> FUNCTION_FIELDS = Map.of(
            "minecraft:set_damage", Map.of("damage", Kind.FLOAT),
            "minecraft:enchanted_count_increase", Map.of("count", Kind.FLOAT),
            "minecraft:set_count", Map.of("count", Kind.INT),
            "minecraft:enchant_with_levels", Map.of("levels", Kind.INT),
            "minecraft:set_ominous_bottle_amplifier", Map.of("amplifier", Kind.INT),
            "minecraft:set_random_dyes", Map.of("number_of_dyes", Kind.INT));
    private static final Map<String, Map<String, Kind>> CONDITION_FIELDS = Map.of(
            "minecraft:random_chance", Map.of("chance", Kind.FLOAT),
            "minecraft:value_check", Map.of("value", Kind.INT));
    // Ranges of whole numbers: each bound is an int provider.
    private static final Map<String, List<String>> CONDITION_RANGES = Map.of(
            "minecraft:value_check", List.of("range"),
            "minecraft:time_check", List.of("value"));

    @Override
    public String name() {
        return "legacy number providers";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.targetAtLeast(FIRST_SPLIT_PROVIDER_HOST) && context.sourceBefore(FIRST_SPLIT_PROVIDER_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        int changed = 0;
        for (String name : tree.names()) {
            String path = ModDataMigrator.dataPath(name);
            if (!path.startsWith("data/") || !path.endsWith(".json") || path.contains("/worldgen/")) continue;
            byte[] before = tree.read(name, MAX_FILE_BYTES);
            String text = new String(before, StandardCharsets.UTF_8);
            // Only files that name a provider kind 26.3 split, or a range inside a range, can change.
            if (!text.contains("score") && !text.contains("binomial") && !text.contains("\"min\"")) continue;
            JsonElement root = parse(text);
            if (root == null) continue; // a malformed file is left for the steps after this one
            boolean[] fileChanged = {false};
            walk(root, fileChanged);
            if (!fileChanged[0]) continue;
            tree.write(name, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
            changed++;
        }
        return changed;
    }

    private static JsonElement parse(String text) {
        try {
            JsonSecurity.validate(text, MAX_FILE_BYTES, MAX_JSON_DEPTH, "Data JSON");
            return JsonParser.parseString(text);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Rewrites every provider field of every pool, function and condition in the tree. */
    private static void walk(JsonElement element, boolean[] changed) {
        if (element instanceof JsonArray array) {
            for (JsonElement item : array) walk(item, changed);
            return;
        }
        if (!(element instanceof JsonObject object)) return;
        for (JsonElement child : object.asMap().values()) walk(child, changed);
        // A pool rolls a whole number of times, and adds a fraction of bonus rolls per luck.
        if (object.has("entries")) {
            field(object, "rolls", Kind.INT, changed);
            field(object, "bonus_rolls", Kind.FLOAT, changed);
        }
        String function = kind(object, "function");
        if (function != null) {
            FUNCTION_FIELDS.getOrDefault(function, Map.of()).forEach((key, kind) -> field(object, key, kind, changed));
            if (function.equals("minecraft:set_attributes") && object.get("modifiers") instanceof JsonArray modifiers) {
                for (JsonElement modifier : modifiers) {
                    if (modifier instanceof JsonObject entry) field(entry, "amount", Kind.FLOAT, changed);
                }
            }
            if (function.equals("minecraft:limit_count")) range(object.get("limit"), changed);
        }
        String condition = kind(object, "condition");
        if (condition != null) {
            CONDITION_FIELDS.getOrDefault(condition, Map.of()).forEach((key, kind) -> field(object, key, kind, changed));
            for (String key : CONDITION_RANGES.getOrDefault(condition, List.of())) range(object.get(key), changed);
            if (condition.equals("minecraft:entity_scores") && object.get("scores") instanceof JsonObject scores) {
                for (JsonElement score : scores.asMap().values()) range(score, changed);
            }
        }
    }

    private static void field(JsonObject owner, String key, Kind kind, boolean[] changed) {
        JsonElement value = owner.get(key);
        if (value == null) return;
        JsonElement converted = provider(value, kind, changed);
        if (converted != value) owner.add(key, converted);
    }

    /** A {@code {min, max}} range of whole numbers, whose bounds are int providers. */
    private static void range(JsonElement value, boolean[] changed) {
        if (!(value instanceof JsonObject range) || range.has("type")) return;
        for (String bound : new String[] {"min", "max"}) field(range, bound, Kind.INT, changed);
    }

    /** The provider rewritten for a field of {@code kind}; the same element when nothing changed. */
    private static JsonElement provider(JsonElement value, Kind kind, boolean[] changed) {
        if (!(value instanceof JsonObject provider)) return value;
        String type = provider.get("type") instanceof JsonPrimitive primitive && primitive.isString()
                ? namespaced(primitive.getAsString()) : null;
        // A bare {min, max} where a provider is read was a uniform; 26.3 reads one only with its type.
        if (type == null && provider.size() == 2 && provider.has("min") && provider.has("max")) {
            provider.addProperty("type", "minecraft:uniform");
            changed[0] = true;
            type = "minecraft:uniform";
        }
        if (type == null) return value;
        switch (type) {
            case "minecraft:uniform" -> {
                for (String bound : new String[] {"min", "max"}) field(provider, bound, kind, changed);
            }
            case "minecraft:binomial" -> {
                binomialTrials(provider, changed);
                field(provider, "p", Kind.FLOAT, changed);
                // 26.3 has binomial only as a whole number; a fraction field reads it through from_int.
                if (kind == Kind.FLOAT) return fromInt(provider, changed);
            }
            case "minecraft:score" -> {
                // 26.3 has score only as a whole number. The old fraction was the score times its scale.
                if (kind == Kind.FLOAT) return scaledScore(provider, changed);
            }
            default -> {
            }
        }
        return value;
    }

    /** The old binomial rounded its trials to a whole number, which 26.3 reads only as one. */
    private static void binomialTrials(JsonObject binomial, boolean[] changed) {
        JsonElement trials = binomial.get("n");
        if (trials instanceof JsonPrimitive number && number.isNumber()
                && number.getAsBigDecimal().stripTrailingZeros().scale() > 0) {
            binomial.addProperty("n", Math.round(number.getAsFloat()));
            changed[0] = true;
        } else if (trials != null) {
            field(binomial, "n", Kind.INT, changed);
        }
    }

    private static JsonObject scaledScore(JsonObject score, boolean[] changed) {
        JsonElement scale = score.remove("scale");
        JsonObject asFloat = fromInt(score, changed);
        if (scale == null || scale instanceof JsonPrimitive one && one.isNumber() && one.getAsDouble() == 1.0) {
            return asFloat;
        }
        JsonArray inputs = new JsonArray();
        inputs.add(asFloat);
        inputs.add(scale);
        JsonObject product = new JsonObject();
        product.addProperty("type", "minecraft:mul");
        product.add("inputs", inputs);
        return product;
    }

    private static JsonObject fromInt(JsonObject whole, boolean[] changed) {
        JsonObject asFloat = new JsonObject();
        asFloat.addProperty("type", "minecraft:from_int");
        asFloat.add("input", whole);
        changed[0] = true;
        return asFloat;
    }

    /** The namespaced function or condition named under {@code key}, or null. */
    private static String kind(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                ? namespaced(primitive.getAsString()) : null;
    }

    private static String namespaced(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }
}
