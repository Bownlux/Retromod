/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.Reader;

/** Stands in for {@code StrictJsonParser}, which reads a client asset's JSON with Minecraft's Gson. */
public final class StrictJsonParser {
    private StrictJsonParser() {}

    public static JsonElement parse(Reader reader) {
        return JsonParser.parseReader(reader);
    }
}
