/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/** Exact metadata migrations for Fabric loader dependency identifiers. */
public final class FabricMetadataCompat {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private FabricMetadataCompat() {}

    /**
     * Replace the retired Fabric API umbrella dependency ID with its current ID.
     * If both IDs are present, the explicit current dependency wins.
     */
    public static byte[] migrateLegacyFabricApiDependency(byte[] jsonData) {
        try {
            JsonObject root = JsonParser.parseString(
                    new String(jsonData, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("depends") || !root.get("depends").isJsonObject()) {
                return jsonData;
            }

            JsonObject depends = root.getAsJsonObject("depends");
            if (!depends.has("fabric")) {
                return jsonData;
            }

            if (!depends.has("fabric-api")) {
                depends.add("fabric-api", depends.get("fabric"));
            }
            depends.remove("fabric");
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            return jsonData;
        }
    }
}
