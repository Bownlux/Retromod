/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

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

/** Gives the 1.12 converters the files {@link ModDataLayoutMigrator} copied into {@code data/}. */
final class LegacyDataCopies {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 256;

    private LegacyDataCopies() {}

    /** The copies of one data kind, such as {@code recipe}, that still exist in the tree. */
    static List<MigrationLedger.Relocation> of(ResourceTree tree, MigrationContext context,
                                               String kind) {
        // Only the copies change. The originals stay as shipped, since 1.12 code can read them from
        // the classpath. A data/ file the jar ships itself is its author's 1.13 version, whose ids
        // must not be flattened twice: 1.12 minecraft:grass is the grass block, in 1.13 the short plant.
        List<MigrationLedger.Relocation> copies = new ArrayList<>();
        for (MigrationLedger.Relocation copy : context.ledger().relocations()) {
            if (copy.legacyAssetSide() && copy.kind().equals(kind) && tree.exists(copy.to())) {
                copies.add(copy);
            }
        }
        return copies;
    }

    /** The namespace of a {@code data/<ns>/...} or {@code assets/<ns>/...} path. */
    static String namespace(String path) {
        int start = path.indexOf('/') + 1;
        return path.substring(start, path.indexOf('/', start));
    }

    /** Reads a JSON object as 1.12 wrote it, or null, leaving a bad file for the load gate to judge. */
    static JsonObject read(ResourceTree tree, String name) {
        JsonElement root = readElement(tree, name);
        return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    /** Reads any JSON value as 1.12 wrote it, or null when the file does not parse. */
    static JsonElement readElement(ResourceTree tree, String name) {
        try {
            // 1.12 accepted comments and trailing commas, so they are stripped first.
            byte[] bytes = ModDataMigrator.normalizeLenientJson(tree.read(name, MAX_JSON_BYTES));
            JsonSecurity.validate(bytes, MAX_JSON_BYTES, MAX_JSON_DEPTH, "1.12 data JSON");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static void write(ResourceTree tree, String name, JsonElement json) throws IOException {
        tree.write(name, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }
}
