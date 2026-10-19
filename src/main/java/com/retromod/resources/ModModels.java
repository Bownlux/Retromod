/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Reads the block and item models a mod ships, the way the model converters need them. */
final class ModModels {

    /** A reference without a namespace means this one, in 1.12 as now. */
    static final String DEFAULT_NAMESPACE = "minecraft";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private ModModels() {
    }

    /** Model files under {@code assets/<ns>/models/} for the given namespaces, by entry name. */
    static List<String> modelFiles(SortedSet<String> names, Set<String> namespaces) {
        // Only the mod's own asset root counts, so a resource pack it ships in a subfolder keeps
        // the format its own pack.mcmeta declares.
        return filesUnder(names, namespaces, "models/", ".json");
    }

    /** Files with {@code extension} under {@code assets/<ns>/<folder>} for the given namespaces. */
    static List<String> filesUnder(SortedSet<String> names, Set<String> namespaces, String folder,
                                   String extension) {
        List<String> files = new ArrayList<>();
        for (String namespace : namespaces) {
            // Every name with the prefix sorts between it and the prefix plus the highest character.
            String prefix = "assets/" + namespace + "/" + folder;
            for (String name : names.subSet(prefix, prefix + Character.MAX_VALUE)) {
                if (name.endsWith(extension)) files.add(name);
            }
        }
        return files;
    }

    /** {@code assets/ns/models/block/x.json} becomes {@code ns:block/x}; null for any other name. */
    static String modelId(String entryName) {
        if (!entryName.startsWith("assets/") || !entryName.endsWith(".json")) return null;
        String rest = entryName.substring("assets/".length(), entryName.length() - ".json".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || !rest.startsWith("models/", slash + 1)) return null;
        return rest.substring(0, slash) + ":" + rest.substring(slash + 1 + "models/".length());
    }

    /** The file a model id names: {@code ns:block/x} becomes {@code assets/ns/models/block/x.json}. */
    static String modelEntry(String modelId) {
        int colon = modelId.indexOf(':');
        return "assets/" + modelId.substring(0, colon) + "/models/" + modelId.substring(colon + 1) + ".json";
    }

    /** The PNG a texture id names. */
    static String textureEntry(String textureId) {
        int colon = textureId.indexOf(':');
        return "assets/" + textureId.substring(0, colon) + "/textures/" + textureId.substring(colon + 1) + ".png";
    }

    /** A reference with its namespace written out, or null when it is not a valid identifier. */
    static String qualify(String reference) {
        if (reference == null || reference.isEmpty()) return null;
        int colon = reference.indexOf(':');
        String namespace = colon < 0 ? DEFAULT_NAMESPACE : reference.substring(0, colon);
        String path = colon < 0 ? reference : reference.substring(colon + 1);
        // Old mods sometimes wrote capitals, which no current game accepts, so those are not guessed at.
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) return null;
        return namespace + ":" + path;
    }

    static String namespace(String qualifiedId) {
        return qualifiedId.substring(0, qualifiedId.indexOf(':'));
    }

    static String path(String qualifiedId) {
        return qualifiedId.substring(qualifiedId.indexOf(':') + 1);
    }

    /** Writes {@code newId} like {@code original}, which may leave out the {@code minecraft} namespace. */
    static String sameForm(String original, String newId) {
        if (original.indexOf(':') < 0 && namespace(newId).equals(DEFAULT_NAMESPACE)) return path(newId);
        return newId;
    }

    /** The model as an object, or null when it is unreadable or not an object. */
    static JsonObject read(ResourceTree tree, String name) {
        // Parsed leniently, since old Minecraft accepted comments and trailing commas in models.
        // A model that does not parse is left for the game to report.
        try {
            JsonElement parsed = JsonParser.parseString(
                    new String(tree.read(name), StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Writes a model a converter changed back as strict JSON. */
    static void write(ResourceTree tree, String name, JsonObject model) throws IOException {
        tree.write(name, model.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The texture a slot names: a plain string, or since 26.1 an object with a {@code sprite}; else null. */
    static String slotReference(JsonElement slot) {
        if (slot == null) return null;
        if (slot.isJsonPrimitive() && slot.getAsJsonPrimitive().isString()) return slot.getAsString();
        if (slot.isJsonObject()) {
            JsonElement sprite = slot.getAsJsonObject().get("sprite");
            if (sprite != null && sprite.isJsonPrimitive() && sprite.getAsJsonPrimitive().isString()) {
                return sprite.getAsString();
            }
        }
        return null;
    }

    /** The slot with its texture replaced, keeping the object form and its other fields. */
    static JsonElement withReference(JsonElement slot, String reference) {
        if (slot.isJsonObject()) {
            JsonObject copy = slot.getAsJsonObject().deepCopy();
            copy.addProperty("sprite", reference);
            return copy;
        }
        return new JsonPrimitive(reference);
    }

    /** The model's own {@code textures} object, or null when it has none. */
    static JsonObject textures(JsonObject model) {
        JsonElement textures = model.get("textures");
        return textures != null && textures.isJsonObject() ? textures.getAsJsonObject() : null;
    }

    /** The model's {@code parent} as written, or null. */
    static String parent(JsonObject model) {
        JsonElement parent = model.get("parent");
        return parent != null && parent.isJsonPrimitive() && parent.getAsJsonPrimitive().isString()
                ? parent.getAsString() : null;
    }

    /** Calls {@code visitor} with every model object a blockstate names. */
    static void blockstateModels(JsonObject blockstate, Consumer<JsonObject> visitor) {
        // Each variant, including the weighted lists.
        JsonElement variants = blockstate.get("variants");
        if (variants != null && variants.isJsonObject()) {
            for (Map.Entry<String, JsonElement> variant : variants.getAsJsonObject().entrySet()) {
                visitModelObjects(variant.getValue(), visitor);
            }
        }
        // Each multipart apply.
        JsonElement multipart = blockstate.get("multipart");
        if (multipart != null && multipart.isJsonArray()) {
            for (JsonElement part : multipart.getAsJsonArray()) {
                if (part.isJsonObject()) visitModelObjects(part.getAsJsonObject().get("apply"), visitor);
            }
        }
    }

    /** Visits one model object, or each object in a weighted list. */
    private static void visitModelObjects(JsonElement value, Consumer<JsonObject> visitor) {
        if (value == null) return;
        if (value.isJsonObject()) {
            visitor.accept(value.getAsJsonObject());
        } else if (value.isJsonArray()) {
            for (JsonElement entry : value.getAsJsonArray()) {
                if (entry.isJsonObject()) visitor.accept(entry.getAsJsonObject());
            }
        }
    }

    // The OBJ material lines that name a texture. Forge's OBJ loader reads the value as a texture id
    // in the blocks atlas, so only a namespaced id is taken; a bare file name means something else.
    private static final Pattern MATERIAL_TEXTURE =
            Pattern.compile("^(\\s*map_(?:Kd|Ka|d)\\s+)([a-z0-9_.-]+:[a-z0-9_./-]+)(\\s*)$");

    /** The texture ids an OBJ material file names, in file order. */
    static List<String> materialTextures(String material) {
        List<String> textures = new ArrayList<>();
        rewriteMaterialTextures(material, texture -> {
            textures.add(texture);
            return texture;
        });
        return textures;
    }

    /** The material file with each texture id passed through {@code rename}. */
    static String rewriteMaterialTextures(String material, java.util.function.UnaryOperator<String> rename) {
        StringBuilder out = new StringBuilder(material.length());
        // Split after each newline, so line endings and every other line are kept as they were.
        for (String line : material.split("(?<=\n)", -1)) {
            // Match the line without its ending, then put the ending back.
            int end = line.length();
            if (end > 0 && line.charAt(end - 1) == '\n') end--;
            if (end > 0 && line.charAt(end - 1) == '\r') end--;
            java.util.regex.Matcher texture = MATERIAL_TEXTURE.matcher(line.substring(0, end));
            if (texture.matches()) {
                out.append(texture.group(1)).append(rename.apply(texture.group(2)))
                        .append(texture.group(3)).append(line, end, line.length());
            } else {
                out.append(line);
            }
        }
        return out.toString();
    }

    /** Parses each model once per converter run, since parent chains revisit the same files. */
    static final class Cache {
        private final ResourceTree tree;
        private final Map<String, JsonObject> models = new LinkedHashMap<>();

        Cache(ResourceTree tree) {
            this.tree = tree;
        }

        /** The model a qualified id names in this jar, or null when the jar does not ship it. */
        JsonObject model(String modelId) {
            if (modelId == null) return null;
            return models.computeIfAbsent(modelId, id -> {
                String entry = modelEntry(id);
                return tree.exists(entry) ? read(tree, entry) : null;
            });
        }

        /** The model and its ancestors that this jar ships, child first. */
        List<Map.Entry<String, JsonObject>> chain(String modelId) {
            List<Map.Entry<String, JsonObject>> chain = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            String id = modelId;
            // Stop at the first parent the jar does not ship, such as a vanilla template, and at a cycle.
            while (id != null && seen.add(id) && chain.size() < 64) {
                JsonObject model = model(id);
                if (model == null) break;
                chain.add(Map.entry(id, model));
                id = qualify(parent(model));
            }
            return chain;
        }
    }
}
