/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.RetromodLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Keeps each old item model's textures in one atlas on 1.21.11 and newer, which stitch block and item textures apart. */
public final class ItemModelAtlasAliases implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // From 1.21.11 an item model fails to bake when its quads use both the blocks and the items atlas.
    // Minecraft looks a texture up in the items atlas first, then in the blocks atlas.
    static final String FIRST_SPLIT_HOST = ModTextureAtlasSources.FIRST_ITEMS_ATLAS_HOST;
    // A copy of an item texture in the blocks atlas gets an id under this folder, which no texture uses.
    static final String ALIAS_FOLDER = "retromod/blocks_atlas/";
    // Forge's fluid container model reads a fluid's block/ texture under the same path in item/.
    static final String FORGE_FLUID_CONTAINER = "forge:fluid_container";
    private static final List<String> FLUID_FOLDERS = List.of("block/", "block/fluid/", "block/fluids/");

    enum Atlas { BLOCKS, ITEMS }

    @Override
    public String name() {
        return "item model atlas aliases";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.sourceBefore(FIRST_SPLIT_HOST) && context.targetAtLeast(FIRST_SPLIT_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // Only the mod's own item models change; the textures stay where they are.
        ModModels.Cache models = new ModModels.Cache(tree);
        Set<String> inBlocks = ModTextureAtlasSources.listedSprites(tree, ModTextureAtlasSources.BLOCKS_ATLAS);
        Set<String> inItems = ModTextureAtlasSources.listedSprites(tree, ModTextureAtlasSources.ITEMS_ATLAS);
        Map<String, String> blockCopies = new TreeMap<>();
        Map<String, String> itemCopies = new TreeMap<>();
        int changed = 0;
        for (String name : ModModels.filesUnder(tree.names(), context.ownNamespaces(), "models/item/", ".json")) {
            String modelId = ModModels.modelId(name);
            JsonObject model = modelId == null ? null : models.model(modelId);
            if (model == null) continue;
            if (context.targetLoader() == MigrationContext.Loader.FORGE) {
                fluidTextures(tree, model, context, itemCopies);
            }
            if (useBlocksAtlas(model, models.chain(modelId), inBlocks, inItems, blockCopies)) {
                ModModels.write(tree, name, model);
                changed++;
            }
        }
        changed += ModTextureAtlasSources.addSources(tree, ModTextureAtlasSources.BLOCKS_ATLAS, blockCopies)
                + ModTextureAtlasSources.addSources(tree, ModTextureAtlasSources.ITEMS_ATLAS, itemCopies);
        if (changed > 0) {
            LOGGER.debug(RetromodLog.DETAIL, "Put {} item texture(s) in the blocks atlas and {} fluid texture(s) in the"
                    + " items atlas for item models on Minecraft {}", blockCopies.size(), itemCopies.size(),
                    context.targetMcVersion());
        }
        return changed;
    }

    /**
     * Points a model that mixes atlases at blocks atlas copies of its item textures; true when the
     * model changed. A model with a texture whose atlas is unknown is left alone.
     */
    static boolean useBlocksAtlas(JsonObject model, List<Map.Entry<String, JsonObject>> chain, Set<String> inBlocks,
            Set<String> inItems, Map<String, String> blockCopies) {
        Map<String, String> textures = slotTextures(chain);
        Set<Atlas> atlases = EnumSet.noneOf(Atlas.class);
        for (String texture : textures.values()) {
            Atlas atlas = atlas(texture, inBlocks, inItems);
            if (atlas == null) return false;
            atlases.add(atlas);
        }
        if (atlases.size() < 2) return false;
        // The model's own textures win over its parents', so each item texture is replaced there.
        String namespace = ModModels.namespace(chain.get(0).getKey());
        JsonObject own = ModModels.textures(model);
        if (own == null) {
            own = new JsonObject();
            model.add("textures", own);
        }
        for (Map.Entry<String, String> slot : textures.entrySet()) {
            if (atlas(slot.getValue(), inBlocks, inItems) != Atlas.ITEMS) continue;
            String copy = namespace + ":" + ALIAS_FOLDER + slot.getValue().replace(':', '/');
            blockCopies.put(copy, slot.getValue());
            // A model that inherits this one then reads the copy as a blocks atlas texture.
            inBlocks.add(copy);
            JsonElement written = own.get(slot.getKey());
            own.add(slot.getKey(), written == null ? new JsonPrimitive(copy) : ModModels.withReference(written, copy));
        }
        return true;
    }

    /** Each slot that names a texture, from the nearest model that sets it, without the particle, which no quad draws. */
    private static Map<String, String> slotTextures(List<Map.Entry<String, JsonObject>> chain) {
        Map<String, String> textures = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, JsonObject> link : chain) {
            JsonObject slots = ModModels.textures(link.getValue());
            if (slots == null) continue;
            for (Map.Entry<String, JsonElement> slot : slots.entrySet()) {
                // A slot a nearer model sets hides the same slot further up, even as a #reference.
                if (!seen.add(slot.getKey()) || slot.getKey().equals("particle")) continue;
                String reference = ModModels.slotReference(slot.getValue());
                if (reference == null || reference.startsWith("#")) continue;
                String texture = ModModels.qualify(reference);
                if (texture != null) textures.put(slot.getKey(), texture);
            }
        }
        return textures;
    }

    /** The atlas Minecraft finds a texture in, or null when the jar does not show it. */
    static Atlas atlas(String texture, Set<String> inBlocks, Set<String> inItems) {
        String path = ModModels.path(texture);
        if (inItems.contains(texture) || path.startsWith("item/")) return Atlas.ITEMS;
        if (inBlocks.contains(texture) || path.startsWith("block/")) return Atlas.BLOCKS;
        return null;
    }

    /** Copies a Forge fluid container's still texture into the items atlas under the item/ path Forge asks for. */
    private static void fluidTextures(ResourceTree tree, JsonObject model, MigrationContext context,
            Map<String, String> itemCopies) {
        if (!FORGE_FLUID_CONTAINER.equals(ModModels.qualify(string(model, "loader")))) return;
        String fluid = ModModels.qualify(string(model, "fluid"));
        if (fluid == null || !context.ownNamespaces().contains(ModModels.namespace(fluid))) return;
        // The model names the fluid, not its texture, so each usual place for a still texture is tried.
        String namespace = ModModels.namespace(fluid);
        String name = ModModels.path(fluid);
        for (String folder : FLUID_FOLDERS) {
            for (String file : List.of(name, name + "_still")) {
                String texture = namespace + ":" + folder + file;
                String sprite = namespace + ":item/" + folder.substring("block/".length()) + file;
                // A real item/ texture of that name already answers Forge's lookup.
                if (tree.exists(ModModels.textureEntry(texture)) && !tree.exists(ModModels.textureEntry(sprite))) {
                    itemCopies.put(sprite, texture);
                }
            }
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
