/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.stream.Stream;

/** Stands in for DataFixerUpper's JsonOps: maps of Gson elements, converted only to itself. */
public class JsonOps implements DynamicOps<JsonElement> {
    public static final JsonOps INSTANCE = new JsonOps();

    @Override
    @SuppressWarnings("unchecked")
    public <U> U convertTo(DynamicOps<U> outOps, JsonElement input) {
        if (outOps != this) throw new UnsupportedOperationException("the test converts only to JSON");
        return (U) input;
    }

    @Override
    public JsonElement createMap(Stream<Pair<JsonElement, JsonElement>> map) {
        JsonObject object = new JsonObject();
        map.forEach(entry -> object.add(entry.getFirst().getAsString(), entry.getSecond()));
        return object;
    }
}
