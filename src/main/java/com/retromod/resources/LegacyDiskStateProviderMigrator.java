/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Names the rule-based state provider that a pre-26.1 {@code minecraft:disk} feature wrote without a
 * type.
 *
 * <p>Before 26.1 the disk feature's {@code state_provider} was always a rule-based provider, so its
 * codec read {@code fallback} and {@code rules} directly. 26.1 made the rule-based provider a
 * registered provider type, {@code minecraft:rule_based_state_provider}, and the disk feature now
 * reads any typed provider. An old disk fails with {@code No key type in MapLike}, and one stale
 * configured feature fails the whole worldgen registry load. The fields are unchanged, so adding the
 * type is the whole migration.
 */
final class LegacyDiskStateProviderMigrator {

    private LegacyDiskStateProviderMigrator() {}

    static final String RULE_BASED_TYPE = "minecraft:rule_based_state_provider";

    /** Whether a data entry can hold a disk feature. Cheap filter before parsing. */
    static boolean mayContainDisk(String entryName, String content) {
        return entryName.contains("/worldgen/") && content.contains("disk");
    }

    /**
     * @return the rewritten file, or {@code json} itself when nothing matched or it does not parse
     */
    static byte[] migrate(byte[] json) {
        try {
            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            return rewrite(root) ? root.toString().getBytes(StandardCharsets.UTF_8) : json;
        } catch (RuntimeException unparseable) {
            return json;
        }
    }

    /** Walks nested features too, since a placed feature can inline its configured feature. */
    private static boolean rewrite(JsonElement element) {
        boolean changed = false;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) changed |= rewrite(child);
            return changed;
        }
        if (!element.isJsonObject()) return false;

        JsonObject object = element.getAsJsonObject();
        if (isDisk(object)) changed |= nameRuleProvider(object.getAsJsonObject("config"));
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            changed |= rewrite(entry.getValue());
        }
        return changed;
    }

    private static boolean isDisk(JsonObject object) {
        JsonElement type = object.get("type");
        if (type == null || !type.isJsonPrimitive()) return false;
        String id = type.getAsString();
        return (id.equals("minecraft:disk") || id.equals("disk"))
                && object.has("config") && object.get("config").isJsonObject();
    }

    private static boolean nameRuleProvider(JsonObject config) {
        JsonElement provider = config.get("state_provider");
        if (provider == null || !provider.isJsonObject()) return false;
        JsonObject rules = provider.getAsJsonObject();
        if (rules.has("type") || !(rules.has("fallback") || rules.has("rules"))) return false;
        JsonObject typed = new JsonObject();
        typed.addProperty("type", RULE_BASED_TYPE);
        for (Map.Entry<String, JsonElement> field : rules.entrySet()) {
            typed.add(field.getKey(), field.getValue());
        }
        if (!typed.has("rules")) typed.add("rules", new JsonArray());
        config.add("state_provider", typed);
        return true;
    }
}
