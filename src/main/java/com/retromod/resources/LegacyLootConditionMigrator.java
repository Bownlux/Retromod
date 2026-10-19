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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Rewrites loot conditions and functions written before Minecraft 26.3 into 26.3's format. */
final class LegacyLootConditionMigrator {

    private static final String ALL_OF = "minecraft:all_of";
    private static final Set<String> LOOT_ENTRY_TYPES = Set.of(
            "item", "tag", "loot_table", "empty", "dynamic", "alternatives", "sequence", "group");

    private LegacyLootConditionMigrator() {}

    /** Loot tables and item modifiers, where functions and tag entries also changed. */
    static boolean isLootFile(String entryName) {
        return entryName.contains("/loot_table/") || entryName.contains("/loot_tables/")
                || entryName.contains("/item_modifier/") || entryName.contains("/item_modifiers/");
    }

    /** A Forge-family global loot modifier, which carries its own conditions. */
    static boolean isLootModifier(String entryName) {
        return entryName.contains("/loot_modifiers/");
    }

    /**
     * Whether the entry is a kind of file that holds loot conditions and still names one the old
     * way. Global loot modifiers count only on a Forge-family host, which reads them.
     */
    static boolean mayContainOldConditions(String entryName, String content, boolean forgeFamilyHost) {
        if (!content.contains("\"condition\"") && !content.contains("\"function\"")) return false;
        return isLootFile(entryName)
                || entryName.contains("/predicate/") || entryName.contains("/predicates/")
                || entryName.contains("/advancement/") || entryName.contains("/advancements/")
                || entryName.contains("/enchantment/")
                || (forgeFamilyHost && isLootModifier(entryName));
    }

    /** Returns the input bytes when nothing was in the old format. */
    static byte[] migrate(byte[] json, boolean lootFile) {
        return migrate(json, lootFile, false);
    }

    /**
     * {@code keepConditionList} is for a global loot modifier on Forge, whose 26.3 codec still
     * reads a {@code "conditions"} list (NeoForge's reads one {@code "condition"}): the conditions
     * inside it take the new format and the list stays.
     */
    static byte[] migrate(byte[] json, boolean lootFile, boolean keepConditionList) {
        // 26.3 ignores the old keys instead of rejecting them, so an old door dropped from both
        // halves and old drops quietly lost their counts and fortune bonuses.
        try {
            JsonElement original = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            JsonElement migrated;
            if (keepConditionList && original.isJsonObject()
                    && isOldConditionList(original.getAsJsonObject().get("conditions"))) {
                JsonObject modifier = original.getAsJsonObject().deepCopy();
                JsonArray converted = new JsonArray();
                for (JsonElement condition : modifier.getAsJsonArray("conditions")) {
                    converted.add(convertCondition(condition.getAsJsonObject(), lootFile));
                }
                modifier.add("conditions", converted);
                migrated = convert(modifier, lootFile);
            } else if (isOldConditionList(original)) {
                migrated = collapse(original.getAsJsonArray(), lootFile);
            } else {
                migrated = convert(original, lootFile);
            }
            return migrated.equals(original) ? json : migrated.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException malformed) {
            return json;
        }
    }

    private static JsonElement convert(JsonElement element, boolean lootFile) {
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                // An advancement's "victims" is a list of condition lists.
                out.add(isOldConditionList(item) ? collapse(item.getAsJsonArray(), lootFile)
                        : convert(item, lootFile));
            }
            return out;
        }
        if (!element.isJsonObject()) return element;
        JsonObject object = element.getAsJsonObject();
        if (isOldCondition(object)) return convertCondition(object, lootFile);
        if (lootFile && isOldFunction(object)) return convertFunction(object, lootFile);

        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            String key = field.getKey();
            JsonElement value = field.getValue();
            if (isLoaderKey(key)) {
                out.add(key, value);
            } else if (lootFile && isStrayFunctionName(object, key, value)) {
                // MCreator writes a "function" name onto drop entries beside their "type". No
                // version ever read it there, and 26.3's load gate keeps out any field the host
                // does not read, which would cost the whole table for a key the game ignored.
                continue;
            } else if (key.equals("conditions") && value.isJsonArray()
                    && (value.getAsJsonArray().isEmpty() || isOldConditionList(value))) {
                if (!value.getAsJsonArray().isEmpty()) {
                    out.add("condition", collapse(value.getAsJsonArray(), lootFile));
                }
            } else if (lootFile && key.equals("functions") && value.isJsonArray()) {
                JsonArray functions = (JsonArray) convert(value, lootFile);
                out.add("modifier", functions.size() == 1 ? functions.get(0) : functions);
            } else if (!key.equals("terms") && isOldConditionList(value)) {
                // An advancement trigger's "player" or "entity" condition list.
                out.add(key, collapse(value.getAsJsonArray(), lootFile));
            } else if (key.equals("tags") && value.isJsonArray()) {
                out.add(key, hashDamageTags(value.getAsJsonArray()));
            } else {
                out.add(key, convert(value, lootFile));
            }
        }
        // A tag entry names its tag in "items", with a #.
        if (lootFile && isTagEntry(out)) {
            String tag = out.remove("name").getAsString();
            out.addProperty("items", tag.startsWith("#") ? tag : "#" + tag);
        }
        return out;
    }

    private static JsonObject convertCondition(JsonObject condition, boolean lootFile) {
        // A condition names its kind in "type" instead of "condition", and block_state_property
        // became match_block with "blocks" and "state".
        String type = condition.get("condition").getAsString();
        boolean matchBlock = type.equals("minecraft:block_state_property");
        JsonObject out = new JsonObject();
        out.addProperty("type", matchBlock ? "minecraft:match_block" : type);
        for (Map.Entry<String, JsonElement> field : condition.entrySet()) {
            String key = field.getKey();
            if (key.equals("condition")) continue;
            if (matchBlock && key.equals("block")) key = "blocks";
            if (matchBlock && key.equals("properties")) key = "state";
            JsonElement value = field.getValue();
            if (field.getKey().equals("terms") && value.isJsonArray()) {
                out.add(key, convert(value, lootFile));
            } else if (field.getKey().equals("tags") && value.isJsonArray()) {
                out.add(key, hashDamageTags(value.getAsJsonArray()));
            } else {
                out.add(key, convert(value, lootFile));
            }
        }
        return out;
    }

    private static JsonObject convertFunction(JsonObject function, boolean lootFile) {
        // A loot function names its kind in "type"; convert moves a "functions" list to "modifier".
        JsonObject out = new JsonObject();
        out.addProperty("type", function.get("function").getAsString());
        for (Map.Entry<String, JsonElement> field : function.entrySet()) {
            String key = field.getKey();
            JsonElement value = field.getValue();
            if (key.equals("function")) continue;
            if (key.equals("conditions") && value.isJsonArray()
                    && (value.getAsJsonArray().isEmpty() || isOldConditionList(value))) {
                if (!value.getAsJsonArray().isEmpty()) {
                    out.add("condition", collapse(value.getAsJsonArray(), lootFile));
                }
            } else {
                out.add(key, convert(value, lootFile));
            }
        }
        return out;
    }

    /** One condition stays itself; several become one {@code all_of}. */
    private static JsonElement collapse(JsonArray conditions, boolean lootFile) {
        JsonArray converted = new JsonArray();
        for (JsonElement condition : conditions) {
            converted.add(convertCondition(condition.getAsJsonObject(), lootFile));
        }
        if (converted.size() == 1) return converted.get(0);
        JsonObject allOf = new JsonObject();
        allOf.addProperty("type", ALL_OF);
        allOf.add("terms", converted);
        return allOf;
    }

    /** Damage type tag predicates ({@code id} and {@code expected}) now name the tag with a #. */
    private static JsonArray hashDamageTags(JsonArray tags) {
        JsonArray out = new JsonArray();
        for (JsonElement tag : tags) {
            if (tag.isJsonObject() && tag.getAsJsonObject().size() == 2
                    && isString(tag.getAsJsonObject().get("id"))
                    && tag.getAsJsonObject().has("expected")) {
                JsonObject copy = tag.getAsJsonObject().deepCopy();
                String id = copy.get("id").getAsString();
                if (!id.startsWith("#")) copy.addProperty("id", "#" + id);
                out.add(copy);
            } else {
                out.add(convert(tag, false));
            }
        }
        return out;
    }

    private static boolean isOldCondition(JsonElement element) {
        if (!element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        // Only the old shape counts. In 26.3's own format a string "condition" names a predicate
        // file and sits beside a "type" or in a pool, so 26.3 data passes through unchanged.
        return isString(object.get("condition")) && !object.has("type") && !object.has("entries");
    }

    private static boolean isOldConditionList(JsonElement element) {
        if (!element.isJsonArray() || element.getAsJsonArray().isEmpty()) return false;
        for (JsonElement item : element.getAsJsonArray()) {
            if (!isOldCondition(item)) return false;
        }
        return true;
    }

    private static boolean isOldFunction(JsonObject object) {
        return isString(object.get("function")) && !object.has("type");
    }

    /** A {@code "function"} name on a loot entry, where no version read it. */
    private static boolean isStrayFunctionName(JsonObject object, String key, JsonElement value) {
        if (!key.equals("function") || !isString(value) || !isString(object.get("type"))) return false;
        // Old set_contents and set_loot_table functions carry a block entity "type" beside their
        // "function", so only an object whose type is a loot entry type qualifies.
        String type = object.get("type").getAsString();
        return LOOT_ENTRY_TYPES.contains(type.startsWith("minecraft:") ? type.substring(10) : type);
    }

    private static boolean isTagEntry(JsonObject object) {
        return isString(object.get("type")) && object.get("type").getAsString().equals("minecraft:tag")
                && isString(object.get("name")) && !object.has("items");
    }

    /** {@code fabric:load_conditions}, {@code neoforge:conditions} and the like keep their own format. */
    private static boolean isLoaderKey(String key) {
        return key.startsWith("fabric:") || key.startsWith("neoforge:") || key.startsWith("forge:");
    }

    private static boolean isString(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString();
    }
}
