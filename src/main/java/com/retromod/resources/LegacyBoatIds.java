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
import java.util.Set;

/** Names the boat entity a mod's worldgen data spawns by its wood, since 1.21.2 split the boat into one entity per wood. */
final class LegacyBoatIds {

    /** The first release with one boat entity per wood, as the harvested id table records; the ids are checked on 1.21.11 and 26.1 to 26.3. */
    static final String FIRST_SPLIT_BOAT_HOST = "1.21.2";

    private static final String BOAT = "minecraft:boat";
    private static final String CHEST_BOAT = "minecraft:chest_boat";
    // The keys that held a boat's wood: the old entity data's own "Type", and Moonlight-based mods' "boat_type".
    private static final Set<String> WOOD_KEYS = Set.of("Type", "boat_type");
    // Each old wood and the entity ids it became. Bamboo became a raft, not a boat.
    private static final Map<String, String[]> WOODS = Map.of(
            "oak", ids("oak_boat", "oak_chest_boat"), "spruce", ids("spruce_boat", "spruce_chest_boat"),
            "birch", ids("birch_boat", "birch_chest_boat"), "jungle", ids("jungle_boat", "jungle_chest_boat"),
            "acacia", ids("acacia_boat", "acacia_chest_boat"), "cherry", ids("cherry_boat", "cherry_chest_boat"),
            "dark_oak", ids("dark_oak_boat", "dark_oak_chest_boat"),
            "mangrove", ids("mangrove_boat", "mangrove_chest_boat"),
            "bamboo", ids("bamboo_raft", "bamboo_chest_raft"));

    private LegacyBoatIds() {
    }

    private static String[] ids(String boat, String chestBoat) {
        return new String[] {"minecraft:" + boat, "minecraft:" + chestBoat};
    }

    /** Whether the rule applies: worldgen data of a mod built before the split, read on a host after it. */
    static boolean appliesTo(String entryName, String sourceMcVersion, String targetMcVersion) {
        if (!entryName.contains("/worldgen/") || !entryName.endsWith(".json")) return false;
        if (targetMcVersion == null || RetromodVersion.compareMcVersions(targetMcVersion, FIRST_SPLIT_BOAT_HOST) < 0) {
            return false;
        }
        // Data of unknown age still names the old entity, which the host no longer has.
        return sourceMcVersion == null || !sourceMcVersion.matches("\\d+(\\.\\d+)*")
                || RetromodVersion.compareMcVersions(sourceMcVersion, FIRST_SPLIT_BOAT_HOST) < 0;
    }

    /** The file with each boat it can place by wood renamed, or the same array when none was. */
    static byte[] migrate(String entryName, byte[] json, String sourceMcVersion, String targetMcVersion) {
        if (!appliesTo(entryName, sourceMcVersion, targetMcVersion)) return json;
        String text = new String(json, StandardCharsets.UTF_8);
        if (!text.contains("\"" + BOAT + "\"") && !text.contains("\"" + CHEST_BOAT + "\"")) return json;
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException unparsable) {
            return json;
        }
        return rename(root) ? root.toString().getBytes(StandardCharsets.UTF_8) : json;
    }

    /** Renames the boat ids in every object below {@code element}. */
    private static boolean rename(JsonElement element) {
        boolean changed = false;
        if (element instanceof JsonArray list) {
            for (JsonElement item : list) changed |= rename(item);
        } else if (element instanceof JsonObject object) {
            changed = renameFields(object);
            for (Map.Entry<String, JsonElement> field : object.entrySet()) changed |= rename(field.getValue());
        }
        return changed;
    }

    /** Renames a field that names the old boat, by the wood the same object gives. */
    private static boolean renameFields(JsonObject object) {
        boolean changed = false;
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            String id = stringValue(field.getValue());
            int kind = BOAT.equals(id) ? 0 : CHEST_BOAT.equals(id) ? 1 : -1;
            if (kind < 0) continue;
            // An old boat without a wood was an oak boat; a wood this table does not know stays as written.
            String wood = woodOf(object);
            String[] successors = WOODS.get(wood == null ? "oak" : wood);
            if (successors == null) continue;
            field.setValue(new JsonPrimitive(successors[kind]));
            changed = true;
        }
        return changed;
    }

    /** The wood named beside the boat id or in one object field beside it, or null when none is. */
    private static String woodOf(JsonObject object) {
        String wood = woodField(object);
        if (wood != null) return wood;
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            if (field.getValue() instanceof JsonObject data) {
                wood = woodField(data);
                if (wood != null) return wood;
            }
        }
        return null;
    }

    private static String woodField(JsonObject object) {
        for (String key : WOOD_KEYS) {
            String value = stringValue(object.get(key));
            // The game wrote the wood with or without its namespace.
            if (value != null) return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
        }
        return null;
    }

    private static String stringValue(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }
}
