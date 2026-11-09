/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;

/** Minecraft 26.2's unbaked geometry for an old geometry: baking runs the old bake and keeps its quads. */
final class LegacyUnbakedGeometryAdapter implements UnbakedGeometry {

    private final IUnbakedGeometry<?> geometry;
    private final Identifier loader;

    LegacyUnbakedGeometryAdapter(IUnbakedGeometry<?> geometry, Identifier loader) {
        this.geometry = geometry;
        this.loader = loader;
    }

    @Override
    public QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name) {
        return bake(slots, baker, state, name, null);
    }

    /** Forge 26.2 and 26.3 call this one with the model's own context; it overrides the default Forge patches in. */
    public QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name,
            IGeometryBakingContext context) {
        String modelName = name == null ? loader.toString() : name.debugName();
        ModelDebugName debugName = name == null ? () -> modelName : name;
        LegacyGeometryContext old = new LegacyGeometryContext(context, slots, modelName);
        // Minecraft's bake catches only exceptions, so a missing class or member in old code is caught here too.
        try {
            // Forge 1.20.1 asked for the models a geometry names before it baked.
            geometry.resolveParents(id -> LegacyModelBakers.getModel(baker, id), old);
            BakedModel model = geometry.bake(old, baker, LegacyModelBakers.sprites(baker, debugName), state,
                    ItemOverrides.EMPTY, LegacyGeometryBakes.location(modelName));
            return LegacyGeometryBakes.quads(model, old, baker, debugName);
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("legacy geometry bake " + loader, "The old geometry loader " + loader + " threw while"
                    + " Minecraft baked " + modelName + ", so the missing model stands in for its models: " + failure);
            return missing(baker);
        }
    }

    // The missing model's own quads, as a model that failed to bake drew in Forge 1.20.1.
    private static QuadCollection missing(ModelBaker baker) {
        BlockStateModelPart part = baker.missingBlockModelPart();
        QuadCollection.Builder quads = new QuadCollection.Builder();
        for (Direction side : Direction.values()) {
            for (BakedQuad quad : part.getQuads(side)) quads.addCulledFace(side, quad);
        }
        List<BakedQuad> unculled = part.getQuads(null);
        for (BakedQuad quad : unculled) quads.addUnculledFace(quad);
        return quads.build();
    }

    @Override
    public String toString() {
        return "LegacyUnbakedGeometryAdapter[" + loader + " " + geometry + "]";
    }
}
