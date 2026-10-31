/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;

import java.util.Map;
import java.util.Set;

/**
 * Rewrites worldgen data for 26.3, which rebuilt it.
 *
 * <p>26.3 merged configured features into {@code worldgen/feature} and configured carvers into
 * {@code worldgen/carver}, dropped the {@code config} wrapper, wrote plain block states as
 * {@code "id"} strings or {@code {"id", "properties"}} objects, renamed the state providers and the
 * {@code random_offset} placement, replaced the old noise parameters, and removed a handful of
 * vanilla-only feature types. Every mod built before 26.3 that ships worldgen data then failed to
 * load it, and a placed feature naming a configured feature that no longer exists leaves the
 * worldgen registry unbound, which stops the server.
 *
 * <p>The rules were checked by loading all of 26.2's vanilla worldgen, converted, on a 26.3 server:
 * features, placed features, biomes, jigsaw structures, structure sets, template pools, and
 * processor lists all load. A feature whose type 26.3 removed becomes {@code minecraft:no_op}, so
 * the registry still binds. Density functions, noise settings, carvers, and vanilla's hardcoded
 * structures changed in ways these rules do not cover. Data already in the 26.3 shape is left
 * as it is.
 */
public final class Worldgen26_3Migrator {

    private static final Map<String, String> FOLDERS = Map.of(
            "/worldgen/configured_feature/", "/worldgen/feature/",
            "/worldgen/configured_carver/", "/worldgen/carver/");

    private static final Map<String, String> TYPE_RENAMES = Map.of(
            "minecraft:weighted_state_provider", "minecraft:weighted",
            "minecraft:rule_based_state_provider", "minecraft:rule_based",
            "minecraft:rotated_block_provider", "minecraft:rotated",
            "minecraft:noise_provider", "minecraft:noise",
            "minecraft:dual_noise_provider", "minecraft:dual_noise",
            "minecraft:noise_threshold_provider", "minecraft:noise_threshold",
            "minecraft:randomized_int_state_provider", "minecraft:randomized_int");

    private static final Set<String> REMOVED_FEATURE_TYPES = Set.of(
            "minecraft:twisting_vines", "minecraft:weeping_vines", "minecraft:seagrass", "minecraft:kelp",
            "minecraft:sea_pickle", "minecraft:coral_mushroom", "minecraft:basalt_columns", "minecraft:basalt_pillar",
            "minecraft:nether_forest_vegetation", "minecraft:glowstone_blob", "minecraft:desert_well");

    private static final java.util.regex.Pattern NAMESPACED_ID = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private Worldgen26_3Migrator() {}

    static boolean appliesTo(String targetMcVersion) {
        return targetMcVersion != null && RetromodVersion.compareMcVersions(targetMcVersion, "26.3") >= 0;
    }

    static boolean isWorldgen(String entryName) {
        return entryName.startsWith("data/") && entryName.contains("/worldgen/") && entryName.endsWith(".json");
    }

    /** Where an entry lives on 26.3: configured features and carvers moved folders. */
    public static String migratedEntryName(String entryName, String targetMcVersion) {
        if (!appliesTo(targetMcVersion) || !isWorldgen(entryName)) return entryName;
        for (Map.Entry<String, String> folder : FOLDERS.entrySet()) {
            int at = entryName.indexOf(folder.getKey());
            if (at > 0) {
                return entryName.substring(0, at) + folder.getValue()
                        + entryName.substring(at + folder.getKey().length());
            }
        }
        return entryName;
    }

    /** The JSON rewritten for 26.3, or the same array when nothing needed to change. */
    static byte[] migrate(byte[] json) {
        JsonElement before;
        try {
            before = com.google.gson.JsonParser.parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        JsonElement after = migrate(before);
        if (after.equals(before)) return json;
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(after)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The element rewritten for 26.3; equal to the input when nothing applied. */
    static JsonElement migrate(JsonElement element) {
        if (element instanceof JsonArray array) {
            JsonArray out = new JsonArray();
            for (JsonElement item : array) out.add(migrate(item));
            return out;
        }
        if (!(element instanceof JsonObject object)) return element;
        if (isOldState(object)) return state(object, false);
        String type = object.has("type") && object.get("type").isJsonPrimitive()
                ? object.get("type").getAsString() : null;

        if (type != null && REMOVED_FEATURE_TYPES.contains(type)) {
            JsonObject noOp = new JsonObject();
            noOp.addProperty("type", "minecraft:no_op");
            return noOp;
        }
        if ("minecraft:simple_state_provider".equals(type) && object.size() == 2 && object.has("state")
                && object.get("state") instanceof JsonObject stateObject && isOldState(stateObject)) {
            // A bare string here would be read as the name of a registered provider.
            return state(stateObject, true);
        }
        if (object.size() == 2 && object.has("firstOctave") && object.get("amplitudes") instanceof JsonArray amplitudes) {
            // "legacy" keeps the old normalization, so the noise samples exactly as before.
            JsonObject noise = new JsonObject();
            noise.add("base_octave", object.get("firstOctave"));
            noise.addProperty("octave_count", amplitudes.size());
            noise.add("amplitude_modifiers", amplitudes);
            noise.addProperty("normalize", "legacy");
            return noise;
        }
        if ("minecraft:random_offset".equals(type)) {
            JsonObject offset = new JsonObject();
            offset.addProperty("type", "minecraft:offset");
            if (object.has("xz_spread")) {
                offset.add("x", migrate(object.get("xz_spread")));
                offset.add("z", migrate(object.get("xz_spread")));
            }
            if (object.has("y_spread")) offset.add("y", migrate(object.get("y_spread")));
            return offset;
        }
        if (type != null && object.size() == 2 && object.get("config") instanceof JsonObject config) {
            JsonObject flat = new JsonObject();
            flat.addProperty("type", type);
            for (Map.Entry<String, JsonElement> field : config.entrySet()) flat.add(field.getKey(), field.getValue());
            return migrate(flat);
        }

        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            out.add(field.getKey(), migrate(field.getValue()));
        }
        if (type != null && TYPE_RENAMES.containsKey(type)) out.addProperty("type", TYPE_RENAMES.get(type));
        return out;
    }

    /** Any data file with only its old block states rewritten; the same array when it has none. */
    static byte[] migrateStates(byte[] json) {
        JsonElement before;
        try {
            before = com.google.gson.JsonParser.parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        JsonElement after = migrateStates(before);
        if (after.equals(before)) return json;
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(after)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static JsonElement migrateStates(JsonElement element) {
        if (element instanceof JsonArray array) {
            JsonArray out = new JsonArray();
            for (JsonElement item : array) out.add(migrateStates(item));
            return out;
        }
        if (!(element instanceof JsonObject object)) return element;
        // Outside worldgen a "Name" key can mean anything, so only a namespaced id with plain
        // property values is read as a state.
        if (isOldState(object) && NAMESPACED_ID.matcher(object.get("Name").getAsString()).matches()
                && hasPlainProperties(object)) {
            return state(object, false);
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) out.add(entry.getKey(), migrateStates(entry.getValue()));
        return out;
    }

    private static boolean hasPlainProperties(JsonObject state) {
        if (!state.has("Properties")) return true;
        if (!(state.get("Properties") instanceof JsonObject properties)) return false;
        for (JsonElement value : properties.asMap().values()) {
            if (!value.isJsonPrimitive()) return false;
        }
        return true;
    }

    /** An old block state object: {@code {"Name": ..., "Properties": {...}}}. */
    private static boolean isOldState(JsonObject object) {
        if (!object.has("Name") || !object.get("Name").isJsonPrimitive()) return false;
        for (String key : object.keySet()) {
            if (!key.equals("Name") && !key.equals("Properties")) return false;
        }
        return true;
    }

    private static JsonElement state(JsonObject old, boolean alwaysObject) {
        JsonElement properties = old.get("Properties");
        boolean hasProperties = properties instanceof JsonObject props && !props.isEmpty();
        if (!hasProperties && !alwaysObject) return new JsonPrimitive(old.get("Name").getAsString());
        JsonObject state = new JsonObject();
        state.add("id", old.get("Name"));
        if (hasProperties) state.add("properties", properties);
        return state;
    }
}
