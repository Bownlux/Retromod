/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.lootstub;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.Function;

/**
 * A codec that reads {@code {"name": ...}} into a loot value and writes the value's name back.
 * JSON with a {@code "bad"} key fails the way a codec reports an unknown entry.
 */
public final class StubCodec {
    private final Function<String, Object> create;

    public StubCodec(Function<String, Object> create) {
        this.create = create;
    }

    public StubResult parse(DynamicOps ops, Object input) {
        JsonElement json = (JsonElement) input;
        if (json.getAsJsonObject().has("bad")) return StubResult.failure("Unknown registry key: bad");
        return StubResult.success(create.apply(json.getAsJsonObject().get("name").getAsString()));
    }

    public StubResult encodeStart(DynamicOps ops, Object value) {
        return StubResult.success(new JsonPrimitive(((LootValue) value).name()));
    }
}
