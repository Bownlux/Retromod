/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.HashSet;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** A block state's model that an old mod supplied whole, baked the old way; the host's model stays as the fallback. */
final class LegacyModelRoot implements BlockStateModel.UnbakedRoot {
    private final UnbakedModel old;
    private final Identifier location;
    private final LegacyModelClaims claims;
    private final BlockStateModel.UnbakedRoot fallback;

    LegacyModelRoot(UnbakedModel old, Identifier location, LegacyModelClaims claims, BlockStateModel.UnbakedRoot fallback) {
        this.old = old;
        this.location = location;
        this.claims = claims;
        this.fallback = fallback;
    }

    @Override
    public void resolveDependencies(ResolvableModel.Resolver resolver) {
        LegacyUnbakedModels.markDependencies(old, resolver, claims, new HashSet<>());
        if (fallback != null) fallback.resolveDependencies(resolver);
    }

    @Override
    public BlockStateModel bake(BlockState state, ModelBaker baker) {
        LegacyClaimingBaker claiming = new LegacyClaimingBaker(baker, claims);
        BlockStateModel model = old instanceof LegacyUnbakedModels.RootModel root
                ? root.root.bake(root.state, claiming)
                : LegacyUnbakedModels.bakeOld(old, claiming, LegacyUnbakedModels.sprites(claiming, location), BlockModelRotation.IDENTITY,
                        location);
        if (model != null) return model;
        // The old model failed to bake, so the block keeps the model 26.x loaded for it.
        return fallback != null ? fallback.bake(state, baker) : new SingleVariant(baker.missingBlockModelPart());
    }

    @Override
    public Object visualEqualityGroup(BlockState state) {
        return this;
    }
}
