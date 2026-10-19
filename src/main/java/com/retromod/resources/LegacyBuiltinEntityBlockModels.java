/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Gives a block whose model inherits {@code builtin/entity} a model the host can still load. */
public final class LegacyBuiltinEntityBlockModels implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // The first release whose model loader no longer knows builtin/entity. The 1.21.2 client's
    // models still use it, and the 1.21.4 client neither ships nor names it.
    static final String REMOVED_IN = "1.21.4";
    private static final String BUILTIN_ENTITY = "minecraft:builtin/entity";
    // New models go in their own folder. The mod's own model files stay as they are, because its
    // items use them.
    static final String GENERATED_FOLDER = "block/retromod/builtin/";

    @Override
    public String name() {
        return "builtin/entity block models";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.sourceBefore(REMOVED_IN) && context.targetAtLeast(REMOVED_IN);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // Blocks drawn by a block entity renderer, such as GeckoLib blocks from MCreator, point
        // their blockstate at a model whose chain ends in builtin/entity. That used to load as an
        // empty model with a particle texture. On the host those blocks show the missing model cube.
        ModModels.Cache models = new ModModels.Cache(tree);
        Map<String, Optional<String>> replacements = new HashMap<>();
        int changed = 0;
        for (String name : ModModels.filesUnder(tree.names(), context.ownNamespaces(), "blockstates/", ".json")) {
            JsonObject blockstate = ModModels.read(tree, name);
            if (blockstate == null) continue;
            List<JsonObject> variants = new ArrayList<>();
            ModModels.blockstateModels(blockstate, variants::add);
            boolean repointed = false;
            for (JsonObject variant : variants) {
                String modelId = ownModel(variant, context);
                if (modelId == null) continue;
                // Each model is checked once, and the answer is reused for every variant naming it.
                Optional<String> replacement = replacements.get(modelId);
                if (replacement == null) {
                    Replacement made = replacementFor(tree, models, modelId);
                    replacement = Optional.ofNullable(made == null ? null : made.modelId());
                    replacements.put(modelId, replacement);
                    if (made != null && made.written()) changed++;
                }
                // Only the model changes, so the variant keeps its rotation.
                if (replacement.isPresent()) {
                    variant.addProperty("model", replacement.get());
                    repointed = true;
                }
            }
            if (repointed) {
                ModModels.write(tree, name, blockstate);
                changed++;
            }
        }
        if (changed > 0) {
            LOGGER.info("Gave block entity blocks particle-only models in {} file(s)", changed);
        }
        return changed;
    }

    /** The model a variant names, when it is in one of the mod's own namespaces. */
    private static String ownModel(JsonObject variant, MigrationContext context) {
        JsonElement model = variant.get("model");
        if (model == null || !model.isJsonPrimitive() || !model.getAsJsonPrimitive().isString()) return null;
        String modelId = ModModels.qualify(model.getAsString());
        return modelId != null && context.ownNamespaces().contains(ModModels.namespace(modelId)) ? modelId : null;
    }

    private record Replacement(String modelId, boolean written) {
    }

    /** The particle-only model that stands in for {@code modelId}, written when missing, or null. */
    private static Replacement replacementFor(ResourceTree tree, ModModels.Cache models, String modelId)
            throws IOException {
        // Only a chain that ends in builtin/entity and has no geometry of its own is replaced.
        List<Map.Entry<String, JsonObject>> chain = models.chain(modelId);
        if (chain.isEmpty()) return null;
        JsonObject root = chain.get(chain.size() - 1).getValue();
        if (!BUILTIN_ENTITY.equals(ModModels.qualify(ModModels.parent(root)))) return null;
        for (Map.Entry<String, JsonObject> link : chain) {
            if (link.getValue().has("elements")) return null; // it draws something itself
        }
        String particle = particleTexture(chain);
        if (particle == null) {
            LOGGER.warn("Left {} unchanged: it inherits builtin/entity, which Minecraft {} and newer do "
                    + "not load, and names no texture to use for its particles", modelId, REMOVED_IN);
            return null;
        }
        // A model holding only the particle texture, the shape vanilla uses for its own block entity
        // blocks such as the chest. Breaking particles then keep their texture.
        String replacement = ModModels.namespace(modelId) + ":" + GENERATED_FOLDER + ModModels.path(modelId);
        String entry = ModModels.modelEntry(replacement);
        JsonObject model = new JsonObject();
        JsonObject textures = new JsonObject();
        textures.addProperty("particle", particle);
        model.add("textures", textures);
        if (tree.exists(entry)) {
            // Written by an earlier run, or a file of the mod's own, which is never replaced.
            return model.equals(ModModels.read(tree, entry)) ? new Replacement(replacement, false) : null;
        }
        ModModels.write(tree, entry, model);
        return new Replacement(replacement, true);
    }

    /** The texture the chain uses for particles, or null when it names none. */
    static String particleTexture(List<Map.Entry<String, JsonObject>> chain) {
        // Gather every texture slot. The chain starts at the leaf, so the leaf-most model's value wins.
        Map<String, String> slots = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> link : chain) {
            JsonObject textures = ModModels.textures(link.getValue());
            if (textures == null) continue;
            for (Map.Entry<String, JsonElement> slot : textures.entrySet()) {
                String reference = ModModels.slotReference(slot.getValue());
                if (reference != null) slots.putIfAbsent(slot.getKey(), reference);
            }
        }
        // Use the particle slot, or failing that the first texture the leaf-most model names.
        String particle = resolve(slots, slots.get("particle"));
        if (particle != null) return particle;
        for (String reference : slots.values()) {
            String resolved = resolve(slots, reference);
            if (resolved != null) return resolved;
        }
        return null;
    }

    /** Follows {@code #slot} references to a texture, or null when they lead nowhere. */
    private static String resolve(Map<String, String> slots, String reference) {
        for (int depth = 0; reference != null && reference.startsWith("#") && depth < 16; depth++) {
            reference = slots.get(reference.substring(1));
        }
        return reference == null || reference.isEmpty() || reference.startsWith("#") ? null : reference;
    }
}
