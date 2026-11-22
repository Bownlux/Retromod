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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gives old armor trim materials the palette id 26.3 requires, and copies their palette to where 26.3 reads it. */
final class TrimMaterialPalettes implements ModResourceConverter {

    static final String FIRST_PALETTE_ID_HOST = "26.3";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final long MAX_FILE_BYTES = 1024L * 1024;
    private static final Pattern MATERIAL = Pattern.compile("data/([a-z0-9_.-]+)/trim_material/.+\\.json");
    private static final Pattern ASSET_NAME = Pattern.compile("[a-z0-9_.-]+");

    @Override
    public String name() {
        return "trim material palettes";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.targetAtLeast(FIRST_PALETTE_ID_HOST) && context.sourceBefore(FIRST_PALETTE_ID_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        int changed = 0;
        for (String name : tree.names()) {
            Matcher material = MATERIAL.matcher(ModDataMigrator.dataPath(name));
            if (!material.matches()) continue;
            changed += migrate(tree, name, material.group(1));
        }
        return changed;
    }

    /** One material: the palette id from its old asset name, and the palette copied once to the new folder. */
    private static int migrate(ResourceTree tree, String name, String namespace) throws IOException {
        JsonObject material;
        try {
            JsonElement parsed = JsonParser.parseString(new String(tree.read(name, MAX_FILE_BYTES), StandardCharsets.UTF_8));
            if (!(parsed instanceof JsonObject object)) return 0;
            material = object;
        } catch (RuntimeException malformed) {
            return 0;
        }
        JsonElement asset = material.get("asset_name");
        if (material.has("palette_id") || asset == null || !asset.isJsonPrimitive()
                || !ASSET_NAME.matcher(asset.getAsString()).matches()) {
            return 0;
        }
        String assetName = asset.getAsString();
        int changed = 0;
        // Old palettes lived in the material's own namespace; a name the mod ships no palette for is vanilla's.
        String oldPalette = "assets/" + namespace + "/textures/trims/color_palettes/" + assetName + ".png";
        String paletteNamespace = tree.exists(oldPalette) ? namespace : "minecraft";
        // Never into the shared minecraft namespace, where the copy would replace vanilla's palette for every pack.
        if (paletteNamespace.equals(namespace) && !namespace.equals("minecraft")) {
            String newPalette = "assets/" + namespace + "/textures/palettes/trim/" + assetName + ".png";
            if (!tree.exists(newPalette)) {
                tree.write(newPalette, tree.read(oldPalette));
                changed++;
            }
        }
        material.addProperty("palette_id", paletteNamespace + ":trim/" + assetName);
        tree.write(name, GSON.toJson(material).getBytes(StandardCharsets.UTF_8));
        return changed + 1;
    }
}
