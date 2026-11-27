/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** A block state's 26.x model whose model ids an old resolver may claim; a single claimed variant becomes the old model. */
final class LegacyResolvingRoot implements BlockStateModel.UnbakedRoot {
    private final BlockStateModel.UnbakedRoot original;
    private final LegacyModelClaims claims;

    LegacyResolvingRoot(BlockStateModel.UnbakedRoot original, LegacyModelClaims claims) {
        this.original = original;
        this.claims = claims;
    }

    @Override
    public void resolveDependencies(ResolvableModel.Resolver resolver) {
        Set<Identifier> seen = new HashSet<>();
        // A claimed id has no model file to load; what the old model needs is loaded instead.
        original.resolveDependencies(id -> {
            UnbakedModel claimed = claims.claim(id);
            if (claimed == null) resolver.markDependency(id);
            else if (seen.add(id)) LegacyUnbakedModels.markDependencies(claimed, resolver, claims, seen);
        });
    }

    @Override
    public BlockStateModel bake(BlockState state, ModelBaker baker) {
        BlockStateModel host = original.bake(state, new LegacyClaimingBaker(baker, claims));
        if (!(host instanceof SingleVariant)) return host;
        // A single variant whose one part is a claimed model's quads draws as that old model, with its emit methods.
        List<BlockStateModelPart> parts = new ArrayList<>();
        host.collectParts(RandomSource.create(42L), parts);
        if (parts.size() == 1 && parts.get(0) instanceof SimpleModelWrapper wrapper) {
            BlockStateModel old = claims.bakedFrom(wrapper.quads());
            if (old != null) return old;
        }
        return host;
    }

    @Override
    public Object visualEqualityGroup(BlockState state) {
        return original.visualEqualityGroup(state);
    }
}
