/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How many tint indices a block or item model uses, read from its model JSON and its parents. */
final class ModelTintIndex {

    /** The model or one of its parents could not be read. */
    static final int UNKNOWN = -1;

    /** Tint slots for a model that cannot be read; old item models used indices 0 to 2. */
    static final int FALLBACK_SLOTS = 4;

    private static final String GENERATED = "minecraft:builtin/generated";
    private static final Pattern LAYER = Pattern.compile("layer(\\d+)");
    private static final int MAX_PARENTS = 32;

    private ModelTintIndex() {}

    /**
     * The number of tint slots the model needs: its highest tint index plus one, 0 when no quad is
     * tinted, or {@link #UNKNOWN}. {@code open} returns a reader for a model id such as
     * {@code minecraft:block/leaves}, or throws when the model does not exist.
     */
    static int tintSlots(String modelId, Function<String, Reader> open) {
        int highestLayer = -1;
        String id = normalize(modelId);
        for (int depth = 0; depth < MAX_PARENTS && id != null; depth++) {
            // Generated item geometry tints each layer with the index of that layer.
            if (GENERATED.equals(id)) return highestLayer + 1;
            JsonObject model = read(id, open);
            if (model == null) return UNKNOWN;
            highestLayer = Math.max(highestLayer, highestLayer(model));
            // The nearest model with elements defines the geometry, so its faces decide.
            if (model.has("elements")) return highestFaceTint(model) + 1;
            JsonElement parent = model.get("parent");
            id = parent != null && parent.isJsonPrimitive() ? normalize(parent.getAsString()) : null;
        }
        return id == null ? 0 : UNKNOWN;
    }

    private static JsonObject read(String id, Function<String, Reader> open) {
        try (Reader reader = open.apply(id)) {
            if (reader == null) return null;
            // Old model files carry comments, which vanilla's lenient reader accepted.
            JsonReader json = new JsonReader(reader);
            json.setLenient(true);
            JsonElement element = JsonParser.parseReader(json);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int highestLayer(JsonObject model) {
        int highest = -1;
        if (!(model.get("textures") instanceof JsonObject textures)) return highest;
        for (String key : textures.keySet()) {
            Matcher layer = LAYER.matcher(key);
            if (layer.matches()) highest = Math.max(highest, Integer.parseInt(layer.group(1)));
        }
        return highest;
    }

    private static int highestFaceTint(JsonObject model) {
        int highest = -1;
        if (!model.get("elements").isJsonArray()) return highest;
        for (JsonElement element : model.getAsJsonArray("elements")) {
            if (!(element instanceof JsonObject cuboid) || !(cuboid.get("faces") instanceof JsonObject faces)) {
                continue;
            }
            for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                if (!(face.getValue() instanceof JsonObject value)) continue;
                JsonElement tint = value.get("tintindex");
                if (tint != null && tint.isJsonPrimitive() && tint.getAsJsonPrimitive().isNumber()) {
                    highest = Math.max(highest, tint.getAsInt());
                }
            }
        }
        return highest;
    }

    /** {@code item/foo} as {@code minecraft:item/foo}; vanilla resolves a bare id the same way. */
    private static String normalize(String id) {
        if (id == null || id.isBlank()) return null;
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }
}
