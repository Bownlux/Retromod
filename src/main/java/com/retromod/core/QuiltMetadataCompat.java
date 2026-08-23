/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.retromod.util.JsonSecurity;
import com.retromod.util.ZipSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads and updates Minecraft dependencies in Quilt loader metadata. */
public final class QuiltMetadataCompat {

    public static final long MAX_METADATA_BYTES = 2L * 1024 * 1024;

    private static final int MAX_VERSION_LENGTH = 128;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private QuiltMetadataCompat() {}

    /** Reads bounded Quilt metadata and returns its declared Minecraft version range. */
    public static String readMinecraftVersion(InputStream input) throws IOException {
        return readMinecraftVersion(readBounded(input));
    }

    /** Returns the declared Minecraft version range, or {@code null} when none is present. */
    public static String readMinecraftVersion(byte[] jsonData) throws IOException {
        List<JsonElement> dependencies = minecraftDependencies(parse(jsonData));
        String declaredVersion = null;
        for (JsonElement dependency : dependencies) {
            JsonElement versions = dependency.isJsonObject()
                    ? dependency.getAsJsonObject().get("versions")
                    : null;
            // A string dependency has Quilt's default "*" version constraint.
            if (dependency.isJsonPrimitive()) {
                if (declaredVersion != null && !"*".equals(declaredVersion)) {
                    return null;
                }
                declaredVersion = "*";
                continue;
            }
            if (versions == null || !versions.isJsonPrimitive()
                    || !versions.getAsJsonPrimitive().isString()) {
                return null;
            }
            String candidate = versions.getAsString();
            if (declaredVersion != null && !declaredVersion.equals(candidate)) {
                return null;
            }
            declaredVersion = candidate;
        }
        return declaredVersion;
    }

    /** Updates the existing Minecraft dependency while preserving every unrelated field. */
    public static byte[] updateMinecraftVersion(byte[] jsonData, String targetMcVersion)
            throws IOException {
        String target = validateTargetVersion(targetMcVersion);
        JsonObject root = parse(jsonData);
        JsonArray dependencies = dependencyArray(root);
        if (dependencies == null) return jsonData;

        String newVersion = "=" + target;
        boolean changed = updateMinecraftDependencies(dependencies, newVersion);
        if (!changed) {
            return jsonData;
        }
        return (PRETTY_GSON.toJson(root) + System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8);
    }

    /** String form for already-bounded extracted metadata files. */
    public static String updateMinecraftVersion(String content, String targetMcVersion)
            throws IOException {
        if (content == null) throw new IOException("quilt.mod.json is missing");
        if (content.length() > MAX_METADATA_BYTES) {
            throw new IOException("quilt.mod.json exceeds " + MAX_METADATA_BYTES + " bytes");
        }
        return new String(updateMinecraftVersion(content.getBytes(StandardCharsets.UTF_8),
            targetMcVersion), StandardCharsets.UTF_8);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) throw new IOException("quilt.mod.json input is missing");
        return ZipSecurity.safeReadAllBytes(input, MAX_METADATA_BYTES);
    }

    private static JsonObject parse(byte[] jsonData) throws IOException {
        JsonSecurity.validate(jsonData, MAX_METADATA_BYTES,
                JsonSecurity.DEFAULT_MAX_DEPTH, "quilt.mod.json");
        String content = new String(jsonData, StandardCharsets.UTF_8);
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new IOException("quilt.mod.json root is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Could not parse quilt.mod.json: " + e.getMessage(), e);
        }
    }

    private static List<JsonElement> minecraftDependencies(JsonObject root) {
        List<JsonElement> matches = new ArrayList<>();
        JsonArray dependencies = dependencyArray(root);
        if (dependencies == null) return matches;

        collectMinecraftDependencies(dependencies, matches);
        return matches;
    }

    private static JsonArray dependencyArray(JsonObject root) {
        JsonElement loaderElement = root.get("quilt_loader");
        if (loaderElement == null || !loaderElement.isJsonObject()) return null;
        JsonElement dependsElement = loaderElement.getAsJsonObject().get("depends");
        return dependsElement != null && dependsElement.isJsonArray()
                ? dependsElement.getAsJsonArray()
                : null;
    }

    private static void collectMinecraftDependencies(JsonArray dependencies,
            List<JsonElement> matches) {
        for (JsonElement dependencyElement : dependencies) {
            if (dependencyElement.isJsonArray()) {
                collectMinecraftDependencies(dependencyElement.getAsJsonArray(), matches);
                continue;
            }
            if (dependencyElement.isJsonPrimitive()
                    && dependencyElement.getAsJsonPrimitive().isString()
                    && isMinecraftIdentifier(dependencyElement.getAsString())) {
                matches.add(dependencyElement);
                continue;
            }
            if (!dependencyElement.isJsonObject()) {
                continue;
            }
            JsonObject dependency = dependencyElement.getAsJsonObject();
            JsonElement id = dependency.get("id");
            if (id != null && id.isJsonPrimitive()
                    && id.getAsJsonPrimitive().isString()
                    && isMinecraftIdentifier(id.getAsString())) {
                matches.add(dependency);
            }
        }
    }

    private static boolean updateMinecraftDependencies(JsonArray dependencies,
            String newVersion) {
        boolean changed = false;
        for (int index = 0; index < dependencies.size(); index++) {
            JsonElement dependencyElement = dependencies.get(index);
            if (dependencyElement.isJsonArray()) {
                changed |= updateMinecraftDependencies(
                        dependencyElement.getAsJsonArray(), newVersion);
                continue;
            }
            if (dependencyElement.isJsonPrimitive()
                    && dependencyElement.getAsJsonPrimitive().isString()
                    && isMinecraftIdentifier(dependencyElement.getAsString())) {
                JsonObject replacement = new JsonObject();
                replacement.addProperty("id", dependencyElement.getAsString());
                replacement.addProperty("versions", newVersion);
                dependencies.set(index, replacement);
                changed = true;
                continue;
            }
            if (!dependencyElement.isJsonObject()) {
                continue;
            }
            JsonObject dependency = dependencyElement.getAsJsonObject();
            JsonElement id = dependency.get("id");
            if (id == null || !id.isJsonPrimitive()
                    || !id.getAsJsonPrimitive().isString()
                    || !isMinecraftIdentifier(id.getAsString())) {
                continue;
            }
            JsonElement versions = dependency.get("versions");
            if (versions != null && versions.isJsonPrimitive()
                    && versions.getAsJsonPrimitive().isString()
                    && newVersion.equals(versions.getAsString())) {
                continue;
            }
            dependency.addProperty("versions", newVersion);
            changed = true;
        }
        return changed;
    }

    private static boolean isMinecraftIdentifier(String identifier) {
        if (identifier == null) return false;
        int separator = identifier.lastIndexOf(':');
        String modId = separator >= 0 ? identifier.substring(separator + 1) : identifier;
        return "minecraft".equals(modId);
    }

    private static String validateTargetVersion(String targetMcVersion) throws IOException {
        if (targetMcVersion == null || targetMcVersion.isBlank()
                || targetMcVersion.length() > MAX_VERSION_LENGTH
                || !targetMcVersion.matches("[A-Za-z0-9][A-Za-z0-9._+-]*")) {
            throw new IOException("Target Minecraft version is missing or unsafe");
        }
        return targetMcVersion.trim();
    }

}
