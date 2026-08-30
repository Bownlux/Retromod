/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Points a pack's own models at the textures after they move.
 *
 * <p>Moving {@code textures/blocks/brick.png} to {@code textures/block/bricks.png} is only half the
 * job. A pack that ships its own models still says {@code "all": "blocks/brick"}, and Minecraft
 * resolves that against the new tree, finds nothing, and draws the missing-texture checkerboard. The
 * pack looks converted and is broken, with nothing in the log to explain it.
 *
 * <p>This rewrites the {@code textures} block of every model in the pack using the same renames the
 * files followed, so a reference lands where its file did. Only that block is touched. A
 * {@code parent} names another model rather than a texture, and blockstates name models too, so
 * neither is rewritten.
 *
 * <p>A reference carries no file extension and may carry a namespace, as in
 * {@code minecraft:blocks/brick}. The namespace is preserved. A value beginning with {@code #} is a
 * placeholder that a child model fills in, so it is left alone.
 */
final class PackTextureReferences {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");

    private PackTextureReferences() {}

    /**
     * Rewrites model texture references under every namespace in {@code packDir}.
     *
     * @param blockBasenameRenames pre-1.13 block texture renames, keyed by bare basename
     * @return the number of model files changed
     */
    static int rewrite(Path packDir, Map<String, String> blockBasenameRenames) throws IOException {
        return rewrite(packDir, blockBasenameRenames, Map.of());
    }

    /**
     * The same rewrite with item renames kept separate from block renames.
     *
     * <p>Block and item textures can share a basename while meaning different things, which is why
     * the tables are separate, and a reference has to be matched against the table for its own
     * directory rather than a merged one.
     */
    static int rewrite(Path packDir, Map<String, String> blockBasenameRenames,
            Map<String, String> itemBasenameRenames) throws IOException {
        Path assets = packDir.resolve("assets");
        if (!Files.isDirectory(assets, LinkOption.NOFOLLOW_LINKS)) return 0;

        Map<String, String> references = referenceRenames(blockBasenameRenames, itemBasenameRenames);
        int changed = 0;
        for (Path namespace : PackNamespaces.list(packDir)) {
            Path models = namespace.resolve("models");
            if (!Files.isDirectory(models, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var stream = Files.walk(models)) {
                for (Path model : stream.filter(p -> p.toString().endsWith(".json"))
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .toList()) {
                    if (rewriteModel(model, references)) changed++;
                }
            }
        }
        if (changed > 0) LOGGER.debug("  Updated texture references in {} model(s)", changed);
        return changed;
    }

    /**
     * The renames expressed the way a model writes them: no extension, and the directory rename
     * folded in so {@code blocks/brick} resolves through to {@code block/bricks}.
     */
    private static Map<String, String> referenceRenames(Map<String, String> blockBasenameRenames,
            Map<String, String> itemBasenameRenames) {
        Map<String, String> out = new HashMap<>();
        // A model may name either directory, because the rename and the move both apply.
        for (Map.Entry<String, String> rename : blockBasenameRenames.entrySet()) {
            out.put("block/" + rename.getKey(), "block/" + rename.getValue());
            out.put("blocks/" + rename.getKey(), "block/" + rename.getValue());
        }
        for (Map.Entry<String, String> rename : itemBasenameRenames.entrySet()) {
            out.put("item/" + rename.getKey(), "item/" + rename.getValue());
            out.put("items/" + rename.getKey(), "item/" + rename.getValue());
        }
        for (Map.Entry<String, String> move : LegacyTexturePathMappings.mappings().entrySet()) {
            out.put(stripPng(move.getKey()), stripPng(move.getValue()));
        }
        return out;
    }

    private static String stripPng(String path) {
        return path.endsWith(".png") ? path.substring(0, path.length() - 4) : path;
    }

    private static boolean rewriteModel(Path model, Map<String, String> references)
            throws IOException {
        String source = Files.readString(model, StandardCharsets.UTF_8);
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(source);
            if (!parsed.isJsonObject()) return false;
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            // A pack's own malformed model is Minecraft's problem to report, not ours to rewrite.
            LOGGER.debug("  Left unreadable model unchanged: {}", model.getFileName());
            return false;
        }
        JsonElement textures = root.get("textures");
        if (textures == null || !textures.isJsonObject()) return false;

        JsonObject block = textures.getAsJsonObject();
        boolean changed = false;
        for (String key : List.copyOf(block.keySet())) {
            JsonElement value = block.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            String reference = value.getAsString();
            String rewritten = rewriteReference(reference, references);
            if (!rewritten.equals(reference)) {
                block.addProperty(key, rewritten);
                changed = true;
            }
        }
        if (!changed) return false;
        Files.writeString(model, root.toString(), StandardCharsets.UTF_8);
        return true;
    }

    /** Applies the renames to one reference, keeping any namespace and placeholder marker. */
    static String rewriteReference(String reference, Map<String, String> references) {
        if (reference.isEmpty() || reference.charAt(0) == '#') return reference;

        int colon = reference.indexOf(':');
        String namespace = colon < 0 ? "" : reference.substring(0, colon + 1);
        String path = colon < 0 ? reference : reference.substring(colon + 1);

        String renamed = references.get(path);
        if (renamed != null) return namespace + renamed;

        // No exact rename, but the directory itself moved in 1.13.
        if (path.startsWith("blocks/")) return namespace + "block/" + path.substring("blocks/".length());
        if (path.startsWith("items/")) return namespace + "item/" + path.substring("items/".length());
        return reference;
    }
}
