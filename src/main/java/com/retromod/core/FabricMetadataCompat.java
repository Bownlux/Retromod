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
import java.util.Map;

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

    /**
     * The Fabric Loader each Minecraft line can count on: what Fabric API for that line requires in its
     * own fabric.mod.json (0.116.15+1.21.1, 0.141.4+1.21.11, 0.143.14+26.1 and 0.145.4+26.1.2,
     * 0.152.2+26.2 and 0.158.0+26.2, 0.160.5+26.3). A line not listed relaxes no floor offline.
     */
    private static final Map<String, String> LOADER_FOR_LINE = Map.of(
            "1.21.1", "0.15.11", "1.21.11", "0.17.3", "26.1", "0.18.4", "26.2", "0.18.4", "26.3", "0.19.3");

    /** The newest Fabric Loader a translated mod may ask for: the running one, or offline the one its target line requires. */
    public static String loaderCeiling(String targetMcVersion) {
        String running = runningFabricLoader();
        if (running != null) return running;
        if (targetMcVersion == null) return null;
        String exact = LOADER_FOR_LINE.get(targetMcVersion);
        if (exact != null) return exact;
        // A 26.x release such as 26.1.2 belongs to the 26.1 line.
        String[] parts = targetMcVersion.split("\\.");
        return parts.length >= 2 && !parts[0].equals("1") ? LOADER_FOR_LINE.get(parts[0] + "." + parts[1]) : null;
    }

    // Read by reflection, so the CLI, AOT and the other loaders, which have no Fabric Loader, find none.
    private static String runningFabricLoader() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            Object container = loaderClass.getMethod("getModContainer", String.class).invoke(loader, "fabricloader");
            Object present = ((java.util.Optional<?>) container).orElse(null);
            if (present == null) return null;
            Object metadata = present.getClass().getMethod("getMetadata").invoke(present);
            Object version = metadata.getClass().getMethod("getVersion").invoke(metadata);
            return (String) version.getClass().getMethod("getFriendlyString").invoke(version);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException none) {
            return null;
        }
    }

    /**
     * Keeps the mod's own {@code fabricloader} floor. Fabric Loader picks the Mixin compatibility level
     * of the mod's mixins from the lowest loader its dependency allows, so a lowered floor turns off
     * Mixin features the mod relies on, such as enum extensions from 0.19.0. Upper bounds go, and only
     * a floor above {@code ceiling} comes down, to that loader.
     */
    public static byte[] keepLoaderFloor(byte[] jsonData, String ceiling) {
        try {
            JsonSecurity.validate(jsonData, MAX_METADATA_BYTES,
                    JsonSecurity.DEFAULT_MAX_DEPTH, "fabric.mod.json");
            JsonObject root = JsonParser.parseString(
                    new String(jsonData, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement depends = root.get("depends");
            JsonElement loader = depends != null && depends.isJsonObject()
                    ? depends.getAsJsonObject().get("fabricloader") : null;
            if (loader == null) return jsonData;

            // Fabric accepts any element of an array, so the lowest floor of any element is the mod's.
            String floor = null;
            for (JsonElement alternative : loader.isJsonArray() ? loader.getAsJsonArray() : List.of(loader)) {
                if (!alternative.isJsonPrimitive() || !alternative.getAsJsonPrimitive().isString()) return jsonData;
                String lower = lowerBound(alternative.getAsString());
                if (lower == null) {
                    floor = null;
                    break;
                }
                if (floor == null || RetromodVersion.compareMcVersions(lower, floor) < 0) floor = lower;
            }
            if (floor != null && ceiling != null && RetromodVersion.compareMcVersions(floor, ceiling) > 0) floor = ceiling;
            String kept = floor == null ? "*" : ">=" + floor;
            if (loader.isJsonPrimitive() && kept.equals(loader.getAsString())) return jsonData;
            depends.getAsJsonObject().addProperty("fabricloader", kept);
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            return jsonData;
        }
    }

    /** The lowest loader a Fabric predicate such as ">=0.15.11 <0.17", "^0.14.21" or "0.14.x" allows, or null when it has none. */
    static String lowerBound(String predicate) {
        String lower = null;
        for (String term : predicate.trim().split("\\s+")) {
            if (term.isEmpty() || term.equals("*") || term.startsWith("<")) continue;
            String version = term.replaceFirst("^(>=|>|=|\\^|~)", "");
            // A wildcard part such as the x of 0.14.x leaves the parts before it.
            version = version.replaceFirst("(\\.[xX*])+$", "");
            if (version.isEmpty() || !Character.isDigit(version.charAt(0))) continue;
            if (lower == null || RetromodVersion.compareMcVersions(version, lower) > 0) lower = version;
        }
        return lower;
    }
}
