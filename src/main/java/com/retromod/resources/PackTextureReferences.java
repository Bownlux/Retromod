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

/** Points a pack's own models at the textures after they move. */
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
        return rewrite(packDir, blockBasenameRenames, itemBasenameRenames, false);
    }

    /** The same rewrite, with {@code vanillaOnly} limiting it to {@code minecraft} references. */
    static int rewrite(Path packDir, Map<String, String> blockBasenameRenames,
            Map<String, String> itemBasenameRenames, boolean vanillaOnly) throws IOException {
        return rewrite(packDir, blockBasenameRenames, itemBasenameRenames, vanillaOnly, null, null);
    }

    /** The same rewrite, also following the versioned moves between the pack's format and the target's. */
    static int rewrite(Path packDir, Map<String, String> blockBasenameRenames,
            Map<String, String> itemBasenameRenames, boolean vanillaOnly,
            PackFormat sourceFormat, PackFormat targetFormat) throws IOException {
        Path assets = packDir.resolve("assets");
        if (!Files.isDirectory(assets, LinkOption.NOFOLLOW_LINKS)) return 0;

        // Moving textures/blocks/brick.png to textures/block/bricks.png is only half the job. A pack
        // model that still says "all": "blocks/brick" finds nothing in the new tree, and Minecraft
        // draws the missing-texture checkerboard with nothing in the log. So each reference takes
        // the same renames its file did.
        Map<String, String> references = referenceRenames(blockBasenameRenames, itemBasenameRenames);
        // The versioned moves are the texture-migrations.tsv ones VersionedTexturePathMappings.apply
        // made to the pack's files. Null formats skip them.
        VersionedMoves versioned = sourceFormat == null || targetFormat == null
                ? null : new VersionedMoves(assets, sourceFormat, targetFormat);
        int changed = 0;
        for (Path namespace : PackNamespaces.list(packDir)) {
            Path models = namespace.resolve("models");
            if (!Files.isDirectory(models, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var stream = Files.walk(models)) {
                for (Path model : stream.filter(p -> p.toString().endsWith(".json"))
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .toList()) {
                    if (rewriteModel(model, references, vanillaOnly, versioned)) changed++;
                }
            }
        }
        if (changed > 0) LOGGER.debug("  Updated texture references in {} model(s)", changed);
        return changed;
    }

    /** The pre-1.13 pack renames, for the {@code minecraft} references in a mod's own models. */
    static Map<String, String> flatteningReferenceRenames() {
        // A mod's own textures never go through them: its blocks/brick is not vanilla's brick.
        return referenceRenames(ResourcePackTransformer.blockRenames(), FlatteningTextureRenames.items());
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

    /** The versioned moves for one pack's references. */
    private record VersionedMoves(Path assets, PackFormat sourceFormat, PackFormat targetFormat) {

        private static final java.util.regex.Pattern NAMESPACE =
                java.util.regex.Pattern.compile("[a-z0-9_-][a-z0-9_.-]*");

        String follow(String reference) {
            if (reference.isEmpty() || reference.charAt(0) == '#') return reference;
            int colon = reference.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : reference.substring(0, colon);
            String file = (colon < 0 ? reference : reference.substring(colon + 1)) + ".png";
            if (!NAMESPACE.matcher(namespace).matches()) return reference;

            VersionedTexturePathMappings.Followed moved =
                    VersionedTexturePathMappings.follow(file, sourceFormat, targetFormat);
            if (moved.path().equals(file)) return reference;
            // Follow a move when the pack ships the texture at its end, which is the pack's own file
            // moved there. Otherwise the reference names vanilla's art, and follows only a minecraft
            // move that kept the layout: the 26.2 sign sprites are new art that the old coordinates
            // would cut wrongly. A moved path comes from the checked table, so the lookup stays
            // inside the pack.
            boolean packShipsIt = Files.isRegularFile(assets.resolve(namespace).resolve("textures")
                    .resolve(moved.path()), LinkOption.NOFOLLOW_LINKS);
            boolean vanillaArt = namespace.equals("minecraft") && moved.keepsLayout();
            if (!packShipsIt && !vanillaArt) return reference;
            return reference.substring(0, colon + 1) + stripPng(moved.path());
        }
    }

    private static boolean rewriteModel(Path model, Map<String, String> references,
            boolean vanillaOnly, VersionedMoves versioned) throws IOException {
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
        // Only the textures block is rewritten. A parent names another model rather than a
        // texture, and blockstates name models too, so neither is touched.
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
            // With vanillaOnly, other namespaces keep their textures where they were shipped. The
            // rest take the pre-1.13 renames, then the versioned moves.
            if (vanillaOnly && !namesVanilla(reference)) continue;
            String rewritten = rewriteReference(reference, references);
            if (versioned != null) rewritten = versioned.follow(rewritten);
            if (!rewritten.equals(reference)) {
                block.addProperty(key, rewritten);
                changed = true;
            }
        }
        if (!changed) return false;
        Files.writeString(model, root.toString(), StandardCharsets.UTF_8);
        return true;
    }

    /** Whether a texture reference names a {@code minecraft} texture, written with or without it. */
    private static boolean namesVanilla(String reference) {
        int colon = reference.indexOf(':');
        return colon < 0 || reference.substring(0, colon).equals("minecraft");
    }

    /** Applies the renames to one reference, keeping any namespace and placeholder marker. */
    static String rewriteReference(String reference, Map<String, String> references) {
        // A reference has no file extension and may carry a namespace, as in minecraft:blocks/brick,
        // which is kept. A value starting with # is a placeholder a child model fills in.
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
