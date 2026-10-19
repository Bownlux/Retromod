/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** Adds the textures an old mod's models use to the atlases that stitch them on 1.19.3 and newer. */
public final class ModTextureAtlasSources implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // Before 1.19.3 the game stitched every texture a model named, wherever the file sat, so mods
    // kept art in textures/blocks/, textures/items/, and folders of their own for OBJ textures.
    // 1.19.3 added atlas definitions: the blocks atlas stitches only the block/ and item/ folders
    // and the sprites listed one by one. Every other texture renders as the missing texture.
    static final String FIRST_ATLAS_HOST = "1.19.3";
    /** The first release with a separate items atlas, which took over the {@code item/} folder. */
    static final String FIRST_ITEMS_ATLAS_HOST = "1.21.11";
    // The game reads atlas files through the whole resource stack and merges them, and Forge,
    // NeoForge and Fabric's resource loader each put every mod into that stack. So a mod's own
    // atlas file adds to vanilla's instead of replacing it.
    static final String BLOCKS_ATLAS = "assets/minecraft/atlases/blocks.json";
    static final String ITEMS_ATLAS = "assets/minecraft/atlases/items.json";

    @Override
    public String name() {
        return "mod texture atlas sources";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.sourceBefore(FIRST_ATLAS_HOST) && context.targetAtLeast(FIRST_ATLAS_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // Nothing is moved or renamed. The PNGs keep their paths, so code that binds them directly
        // still finds them.
        SortedSet<String> names = tree.names();
        Map<String, Boolean> usedByBlocks = textureUses(tree, names, context);
        boolean itemsAtlas = context.targetAtLeast(FIRST_ITEMS_ATLAS_HOST);
        Set<String> blockSprites = new TreeSet<>();
        Set<String> itemSprites = new TreeSet<>();
        int blockFacesInItemFolder = 0;
        for (Map.Entry<String, Boolean> use : usedByBlocks.entrySet()) {
            // Skip textures in shared namespaces such as minecraft, and any the jar does not ship.
            String texture = use.getKey();
            if (MigrationContext.SHARED_NAMESPACES.contains(ModModels.namespace(texture))
                    || !tree.exists(ModModels.textureEntry(texture))) {
                continue;
            }
            // Skip a texture an atlas folder already covers on the host.
            String path = ModModels.path(texture);
            boolean inBlockFolder = path.startsWith("block/") || path.startsWith("entity/conduit/");
            boolean inItemFolder = path.startsWith("item/");
            if (use.getValue()) {
                if (inBlockFolder || (inItemFolder && !itemsAtlas)) continue;
                // A block model texture in item/ on 1.21.11 and newer is left alone, because how
                // chunk rendering treats it is unverified.
                if (inItemFolder) {
                    blockFacesInItemFolder++;
                    continue;
                }
                blockSprites.add(texture);
            } else {
                // A texture only item models use goes in the items atlas where the host has one.
                if (inBlockFolder || inItemFolder) continue;
                (itemsAtlas ? itemSprites : blockSprites).add(texture);
            }
        }
        if (blockFacesInItemFolder > 0) {
            LOGGER.debug("Left {} block model texture(s) in item/ out of the blocks atlas", blockFacesInItemFolder);
        }
        int changed = addSingleSources(tree, BLOCKS_ATLAS, blockSprites)
                + addSingleSources(tree, ITEMS_ATLAS, itemSprites);
        if (changed > 0) {
            LOGGER.info("Listed {} mod texture(s) in the atlases Minecraft {} stitches",
                    blockSprites.size() + itemSprites.size(), context.targetMcVersion());
        }
        return changed;
    }

    /** Every texture the mod's models and OBJ materials name, with whether a block uses it. */
    private static Map<String, Boolean> textureUses(ResourceTree tree, SortedSet<String> names,
                                                    MigrationContext context) throws IOException {
        ModModels.Cache models = new ModModels.Cache(tree);
        Set<String> blockModels = blockstateModels(tree, names, context, models);
        Map<String, Boolean> uses = new TreeMap<>();
        for (String name : ModModels.modelFiles(names, context.ownNamespaces())) {
            String modelId = ModModels.modelId(name);
            JsonObject model = modelId == null ? null : models.model(modelId);
            JsonObject textures = model == null ? null : ModModels.textures(model);
            if (textures == null) continue;
            // A model outside models/item/, a model a blockstate names, and an ancestor of either
            // count as block models. Slot references (#name) are not textures.
            boolean block = !ModModels.path(modelId).startsWith("item/") || blockModels.contains(modelId);
            for (Map.Entry<String, JsonElement> slot : textures.entrySet()) {
                String reference = ModModels.slotReference(slot.getValue());
                if (reference == null || reference.startsWith("#")) continue;
                String texture = ModModels.qualify(reference);
                if (texture != null) uses.merge(texture, block, Boolean::logicalOr);
            }
        }
        // OBJ materials are block geometry in Forge's loader.
        for (String name : ModModels.filesUnder(names, context.ownNamespaces(), "models/", ".mtl")) {
            String material = new String(tree.read(name), StandardCharsets.ISO_8859_1);
            for (String reference : ModModels.materialTextures(material)) {
                String texture = ModModels.qualify(reference);
                if (texture != null) uses.put(texture, true);
            }
        }
        return uses;
    }

    /** The models the mod's blockstates name, with their ancestors in the jar. */
    private static Set<String> blockstateModels(ResourceTree tree, SortedSet<String> names,
                                                MigrationContext context, ModModels.Cache models) {
        Set<String> used = new HashSet<>();
        for (String name : ModModels.filesUnder(names, context.ownNamespaces(), "blockstates/", ".json")) {
            JsonObject blockstate = ModModels.read(tree, name);
            if (blockstate == null) continue;
            ModModels.blockstateModels(blockstate, variant -> {
                JsonElement model = variant.get("model");
                if (model == null || !model.isJsonPrimitive() || !model.getAsJsonPrimitive().isString()) return;
                for (Map.Entry<String, JsonObject> link : models.chain(ModModels.qualify(model.getAsString()))) {
                    used.add(link.getKey());
                }
            });
        }
        return used;
    }

    /** Appends a {@code single} source per sprite to one atlas file; returns 1 when it was written. */
    private static int addSingleSources(ResourceTree tree, String atlas, Set<String> sprites) throws IOException {
        if (sprites.isEmpty()) return 0;
        // An atlas file the mod already ships keeps its own sources, and new ones go after them.
        JsonObject definition = new JsonObject();
        JsonArray sources = new JsonArray();
        if (tree.exists(atlas)) {
            definition = ModModels.read(tree, atlas);
            JsonElement listed = definition == null ? null : definition.get("sources");
            if (listed == null || !listed.isJsonArray()) {
                LOGGER.warn("Left {} unchanged: it is not an atlas definition Retromod can add to", atlas);
                return 0;
            }
            sources = listed.getAsJsonArray();
        }
        // Skip sprites the file already lists, then add one single source for each of the rest.
        Set<String> listedSprites = new HashSet<>();
        for (JsonElement source : sources) {
            if (!source.isJsonObject()) continue;
            JsonElement resource = source.getAsJsonObject().get("resource");
            if (resource != null && resource.isJsonPrimitive()) {
                String id = ModModels.qualify(resource.getAsString());
                if (id != null) listedSprites.add(id);
            }
        }
        List<String> added = new ArrayList<>();
        for (String sprite : sprites) {
            if (!listedSprites.contains(sprite)) added.add(sprite);
        }
        if (added.isEmpty()) return 0;
        for (String sprite : added) {
            JsonObject single = new JsonObject();
            single.addProperty("type", "single");
            single.addProperty("resource", sprite);
            sources.add(single);
        }
        definition.add("sources", sources);
        ModModels.write(tree, atlas, definition);
        return 1;
    }
}
