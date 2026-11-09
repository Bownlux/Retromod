/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.util.JsonSecurity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads and migrates Fabric loader dependency metadata. */
public final class FabricMetadataCompat {

    private static final long MAX_METADATA_BYTES = 2L * 1024 * 1024;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private FabricMetadataCompat() {}

    /** The Minecraft ranges in {@code depends.minecraft}, or an empty list when there are none. */
    public static List<String> readMinecraftRanges(byte[] jsonData) {
        try {
            JsonSecurity.validate(jsonData, MAX_METADATA_BYTES,
                    JsonSecurity.DEFAULT_MAX_DEPTH, "fabric.mod.json");
            JsonElement root = JsonParser.parseString(
                    new String(jsonData, StandardCharsets.UTF_8));
            JsonElement depends = root.isJsonObject() ? root.getAsJsonObject().get("depends") : null;
            JsonElement minecraft = depends != null && depends.isJsonObject()
                    ? depends.getAsJsonObject().get("minecraft") : null;
            if (minecraft == null) return List.of();
            // Fabric accepts a mod when any element of an array matches, so each one is a range.
            Iterable<JsonElement> alternatives = minecraft.isJsonArray()
                    ? minecraft.getAsJsonArray() : List.of(minecraft);
            List<String> ranges = new ArrayList<>();
            for (JsonElement alternative : alternatives) {
                if (alternative.isJsonPrimitive() && alternative.getAsJsonPrimitive().isString()) {
                    ranges.add(alternative.getAsString());
                }
            }
            return List.copyOf(ranges);
        } catch (IOException | RuntimeException unreadable) {
            // Metadata Retromod cannot read declares nothing, so the mod counts as unknown.
            return List.of();
        }
    }

    /**
     * Open the {@code java} dependency above its lowest version. Cobblemon declares {@code "21"},
     * which Fabric reads as exactly Java 21 and refuses on the Java 25 that 26.x needs, while the
     * mod's bytecode runs on any newer Java.
     */
    public static byte[] openJavaDependency(byte[] jsonData) {
        try {
            JsonSecurity.validate(jsonData, MAX_METADATA_BYTES,
                    JsonSecurity.DEFAULT_MAX_DEPTH, "fabric.mod.json");
            JsonObject root = JsonParser.parseString(
                    new String(jsonData, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement depends = root.get("depends");
            JsonElement java = depends != null && depends.isJsonObject()
                    ? depends.getAsJsonObject().get("java") : null;
            if (java == null) return jsonData;

            // Fabric accepts any element of an array, so the lowest version of any element is the floor.
            Integer lowest = null;
            for (JsonElement alternative : java.isJsonArray() ? java.getAsJsonArray() : List.of(java)) {
                if (!alternative.isJsonPrimitive() || !alternative.getAsJsonPrimitive().isString()) continue;
                java.util.regex.Matcher version = JAVA_VERSION.matcher(alternative.getAsString());
                while (version.find()) {
                    int major = Integer.parseInt(version.group(1));
                    if (lowest == null || major < lowest) lowest = major;
                }
            }
            String opened = lowest == null ? null : ">=" + lowest;
            if (opened == null || (java.isJsonPrimitive() && opened.equals(java.getAsString()))) return jsonData;
            depends.getAsJsonObject().addProperty("java", opened);
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            return jsonData;
        }
    }

    // A Java version in a Fabric predicate: 21, 17.0.2 or 1.8, not the 0 of a wildcard like 21.x.
    private static final java.util.regex.Pattern JAVA_VERSION =
            java.util.regex.Pattern.compile("(?<![\\d.])(?:1\\.)?(\\d{1,3})(?![\\d])");

    /**
     * Replace the retired Fabric API umbrella dependency ID with its current ID.
     * If both IDs are present, the explicit current dependency wins.
     */
    public static byte[] migrateLegacyFabricApiDependency(byte[] jsonData) {
        try {
            JsonSecurity.validate(jsonData, MAX_METADATA_BYTES,
                    JsonSecurity.DEFAULT_MAX_DEPTH, "fabric.mod.json");
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
        } catch (IOException | RuntimeException ignored) {
            return jsonData;
        }
    }
}
