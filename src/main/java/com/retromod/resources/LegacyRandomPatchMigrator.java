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
import java.util.Set;

/**
 * Rewrites the patch features that 26.1 deleted into a shape 26.1 still reads.
 *
 * <p>Before 26.1, {@code minecraft:random_patch}, {@code minecraft:flower} and
 * {@code minecraft:no_bonemeal_flower} took {@code tries}, {@code xz_spread}, {@code y_spread} and
 * an inner placed feature. Each try offset the origin by a triangular amount on each axis and placed
 * the inner feature there. 26.1 removed all three types: vanilla moved the spread into its placed
 * features as a {@code count} plus a {@code random_offset} with a zero-plateau trapezoid. An old
 * configured feature that names one of them fails with
 * {@code Unknown registry key in worldgen/feature}, and the whole worldgen registry load fails with
 * it, which stops the world from loading.
 *
 * <p>The vanilla conversion splits one file across two, so it cannot be done one file at a time. This
 * keeps the patch inside the configured feature instead: a {@code simple_random_selector} with one
 * placed feature whose placement is that same {@code count} and {@code random_offset}, followed by the
 * inner feature's own placement. That is the same sequence the old feature ran. Missing fields take
 * the old defaults: 128 tries, a spread of 7 horizontally and 3 vertically.
 *
 * <p>The limit: {@code minecraft:flower} also let bone meal spread the configured flowers. That
 * lookup is gone in 26.1, so bone meal on grass no longer grows a mod's flowers.
 */
final class LegacyRandomPatchMigrator {

    private LegacyRandomPatchMigrator() {}

    private static final Set<String> PATCH_TYPES = Set.of(
            "minecraft:random_patch", "minecraft:flower", "minecraft:no_bonemeal_flower");

    private static final int DEFAULT_TRIES = 128;
    private static final int DEFAULT_XZ_SPREAD = 7;
    private static final int DEFAULT_Y_SPREAD = 3;

    /** 26.1's codec bounds: {@code count} takes 0 to 256, {@code random_offset} -16 to 16. */
    private static final int MAX_COUNT = 256;
    private static final int MAX_SPREAD = 16;

    /** Whether a data entry can hold a feature config. Cheap filter before parsing. */
    static boolean mayContainPatch(String entryName, String content) {
        return entryName.contains("/worldgen/")
                && (content.contains("random_patch") || content.contains("flower"));
    }

    /**
     * @return the rewritten file, or {@code json} itself when nothing matched or it does not parse
     */
    static byte[] migrate(byte[] json) {
        try {
            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            boolean[] changed = {false};
            JsonElement rewritten = rewrite(root, changed);
            return changed[0] ? rewritten.toString().getBytes(StandardCharsets.UTF_8) : json;
        } catch (RuntimeException unparseable) {
            return json;
        }
    }

    private static JsonElement rewrite(JsonElement element, boolean[] changed) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) array.set(i, rewrite(array.get(i), changed));
            return array;
        }
        if (!element.isJsonObject()) return element;

        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            entry.setValue(rewrite(entry.getValue(), changed));
        }
        if (!isLegacyPatch(object)) return object;
        changed[0] = true;
        return toSelector(object.getAsJsonObject("config"));
    }

    private static boolean isLegacyPatch(JsonObject object) {
        JsonElement type = object.get("type");
        JsonElement config = object.get("config");
        if (type == null || !type.isJsonPrimitive() || config == null || !config.isJsonObject()) {
            return false;
        }
        return PATCH_TYPES.contains(namespaced(type.getAsString()))
                && config.getAsJsonObject().has("feature");
    }

    /** One configured feature that places the inner feature with the old patch's spread. */
    private static JsonObject toSelector(JsonObject patch) {
        JsonArray placement = new JsonArray();
        placement.add(count(clamp(intOr(patch, "tries", DEFAULT_TRIES), MAX_COUNT)));
        placement.add(randomOffset(clamp(intOr(patch, "xz_spread", DEFAULT_XZ_SPREAD), MAX_SPREAD),
                clamp(intOr(patch, "y_spread", DEFAULT_Y_SPREAD), MAX_SPREAD)));

        JsonElement inner = patch.get("feature");
        JsonObject placed = new JsonObject();
        if (inner.isJsonObject() && inner.getAsJsonObject().has("feature")) {
            // An inline placed feature: run its own placement after the spread, as the old
            // feature did at each offset position.
            JsonObject innerPlaced = inner.getAsJsonObject();
            placed.add("feature", innerPlaced.get("feature"));
            JsonElement innerPlacement = innerPlaced.get("placement");
            if (innerPlacement != null && innerPlacement.isJsonArray()) {
                placement.addAll(innerPlacement.getAsJsonArray());
            }
        } else {
            // A placed feature referenced by id cannot be inlined, so it is wrapped in a
            // selector of one and keeps its own placement.
            placed.add("feature", selectorOf(inner));
        }
        placed.add("placement", placement);
        return selectorOf(placed);
    }

    private static JsonObject selectorOf(JsonElement placedFeature) {
        JsonArray features = new JsonArray();
        features.add(placedFeature);
        JsonObject config = new JsonObject();
        config.add("features", features);
        JsonObject selector = new JsonObject();
        selector.addProperty("type", "minecraft:simple_random_selector");
        selector.add("config", config);
        return selector;
    }

    private static JsonObject count(int tries) {
        JsonObject count = new JsonObject();
        count.addProperty("type", "minecraft:count");
        count.addProperty("count", tries);
        return count;
    }

    /** The old {@code nextInt(s + 1) - nextInt(s + 1)} offset is a zero-plateau trapezoid. */
    private static JsonObject randomOffset(int xzSpread, int ySpread) {
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "minecraft:random_offset");
        offset.add("xz_spread", trapezoid(xzSpread));
        offset.add("y_spread", trapezoid(ySpread));
        return offset;
    }

    private static JsonObject trapezoid(int spread) {
        JsonObject trapezoid = new JsonObject();
        trapezoid.addProperty("type", "minecraft:trapezoid");
        trapezoid.addProperty("max", spread);
        trapezoid.addProperty("min", -spread);
        trapezoid.addProperty("plateau", 0);
        return trapezoid;
    }

    private static int intOr(JsonObject config, String key, int fallback) {
        JsonElement value = config.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsInt() : fallback;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }
}
