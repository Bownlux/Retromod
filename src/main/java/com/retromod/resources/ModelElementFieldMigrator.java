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
import java.util.List;
import java.util.Map;

/** Moves two model fields that 26.x stopped reading to the fields that replaced them. */
public final class ModelElementFieldMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // The first release whose element deserializer reads no shade, only shade_direction_override.
    // The 26.2 deserializer still reads shade.
    static final String SHADE_OVERRIDE_HOST = "26.3";
    /** The first release with {@code force_translucent}, and the first NeoForge without render_type. */
    static final String FORCE_TRANSLUCENT_HOST = "26.1";

    @Override
    public String name() {
        return "26.x model element fields";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return convertsShade(context) || convertsRenderType(context);
    }

    private static boolean convertsShade(MigrationContext context) {
        return context.sourceBefore(SHADE_OVERRIDE_HOST) && context.targetAtLeast(SHADE_OVERRIDE_HOST);
    }

    private static boolean convertsRenderType(MigrationContext context) {
        // Only NeoForge dropped render_type: NeoForge 21.11 reads it, and Forge 26.2 still does.
        return context.targetLoader() == MigrationContext.Loader.NEOFORGE
                && context.sourceBefore(FORCE_TRANSLUCENT_HOST)
                && context.targetAtLeast(FORCE_TRANSLUCENT_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        boolean shade = convertsShade(context);
        boolean renderType = convertsRenderType(context);
        ModModels.Cache models = new ModModels.Cache(tree);
        // Every model is judged before any is changed, so a parent's render_type is read as shipped.
        Map<String, Boolean> translucent = new java.util.HashMap<>();
        List<String> files = ModModels.modelFiles(tree.names(), context.ownNamespaces());
        if (renderType) {
            for (String name : files) {
                String modelId = ModModels.modelId(name);
                if (modelId != null) translucent.put(name, asksForTranslucent(models.chain(modelId)));
            }
        }
        // Rewrite each model: shading on 26.3, translucency on NeoForge 26.1 and newer.
        int changed = 0;
        for (String name : files) {
            JsonObject model = ModModels.read(tree, name);
            if (model == null) continue;
            boolean modelChanged = shade && overrideUnshadedElements(model);
            if (renderType && translucent.getOrDefault(name, false)) {
                modelChanged |= forceTranslucentTextures(model);
            }
            if (modelChanged) {
                ModModels.write(tree, name, model);
                changed++;
            }
        }
        if (changed > 0) {
            LOGGER.info("Updated element shading or translucency in {} model(s) for Minecraft {}", changed,
                    context.targetMcVersion());
        }
        return changed;
    }

    /** Turns each element's {@code "shade": false} into the 26.3 override. */
    static boolean overrideUnshadedElements(JsonObject model) {
        JsonElement elements = model.get("elements");
        if (elements == null || !elements.isJsonArray()) return false;
        boolean changed = false;
        for (JsonElement element : elements.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject cuboid = element.getAsJsonObject();
            JsonElement shade = cuboid.get("shade");
            if (shade == null || !shade.isJsonPrimitive() || !shade.getAsJsonPrimitive().isBoolean()
                    || shade.getAsBoolean() || cuboid.has("shade_direction_override")) {
                continue;
            }
            // The up override lights every face like an upward one, which is what all 333 vanilla
            // unshaded elements became. Without it an old torch flame, cross plant, or glowing part
            // would get directional shading.
            cuboid.remove("shade");
            cuboid.addProperty("shade_direction_override", "up");
            changed = true;
        }
        return changed;
    }

    /** Whether the model or its nearest jar ancestor with a {@code render_type} asks for translucent. */
    static boolean asksForTranslucent(List<Map.Entry<String, JsonObject>> chain) {
        // Forge's loaders inherit the hint down the chain, so the first link that sets it decides.
        for (Map.Entry<String, JsonObject> link : chain) {
            JsonElement renderType = link.getValue().get("render_type");
            if (renderType == null) continue;
            if (!renderType.isJsonPrimitive() || !renderType.getAsJsonPrimitive().isString()) return false;
            // Only translucent needs help. Vanilla 26.1 picks the layer from each sprite's alpha, so a
            // translucent texture with only fully opaque or fully clear pixels would render as cutout.
            // Other render types get the same result from that rule.
            String id = ModModels.qualify(renderType.getAsString());
            return (ModModels.DEFAULT_NAMESPACE + ":translucent").equals(id);
        }
        return false;
    }

    /** Marks each texture the model sets itself as translucent, in the 26.1 form. */
    static boolean forceTranslucentTextures(JsonObject model) {
        JsonObject textures = ModModels.textures(model);
        if (textures == null) return false;
        boolean changed = false;
        for (String slot : List.copyOf(textures.keySet())) {
            JsonElement value = textures.get(slot);
            // Skip #slot references. The particle slot is not drawn in a chunk layer, so it keeps
            // the plain form vanilla's own models use.
            if (!slot.equals("particle") && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && !value.getAsString().startsWith("#")) {
                // {"sprite": ..., "force_translucent": true} is the 26.1 form of the old request.
                JsonObject material = new JsonObject();
                material.addProperty("sprite", value.getAsString());
                material.addProperty("force_translucent", true);
                textures.add(slot, material);
                changed = true;
            }
        }
        return changed;
    }
}
