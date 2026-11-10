/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/** Forge 1.20.1's IGeometryLoader, which read old geometry from a model's JSON; Forge 26.2 kept the name with a new shape. */
public interface LegacyGeometryLoader<T extends IUnbakedGeometry<T>> {

    T read(JsonObject json, JsonDeserializationContext context) throws JsonParseException;
}
