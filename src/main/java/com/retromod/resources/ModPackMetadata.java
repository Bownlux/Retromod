/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes the root pack.mcmeta that Forge 26.x needs before it loads a mod jar's assets and data. */
final class ModPackMetadata implements ModResourceConverter {

    static final String METADATA = "pack.mcmeta";
    /** The first Forge line checked to refuse a mod jar without one. */
    static final String FIRST_REQUIRING_HOST = "26.1";

    @Override
    public String name() {
        return "mod pack metadata";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        // Forge 26.x reports a jar without one as broken resources, and that loading warning makes
        // the client shut its whole game event bus down. NeoForge and Fabric build the pack themselves.
        return context.targetLoader() == MigrationContext.Loader.FORGE && context.targetAtLeast(FIRST_REQUIRING_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // Forge builds a pack for every mod file, so a jar of code alone needs one too.
        if (tree.exists(METADATA)) return 0;
        // The shape Forge's own jar declares for its assets and data: the host's data format.
        PackFormat data = PackFormat.dataTarget(context.targetMcVersion());
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Mod resources");
        pack.addProperty("max_format", data.major());
        pack.add("min_format", version(data));
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        tree.write(METADATA, (root + "\n").getBytes(StandardCharsets.UTF_8));
        return 1;
    }

    private static JsonArray version(PackFormat format) {
        JsonArray version = new JsonArray();
        version.add(format.major());
        version.add(format.minor());
        return version;
    }
}
