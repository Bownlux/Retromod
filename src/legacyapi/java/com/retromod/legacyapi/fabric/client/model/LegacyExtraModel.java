/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedExtraModel;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;

/** A model an old mod added by id, loaded as a 26.x extra model: the model file, or the old model claimed for the id. */
final class LegacyExtraModel implements UnbakedExtraModel<BlockStateModel> {
    private final Identifier id;
    private final LegacyModelClaims claims;

    LegacyExtraModel(Identifier id, LegacyModelClaims claims) {
        this.id = id;
        this.claims = claims;
    }

    @Override
    public void resolveDependencies(ResolvableModel.Resolver resolver) {
        UnbakedModel claimed = claims.claim(id);
        if (claimed == null) {
            resolver.markDependency(id);
            return;
        }
        Set<Identifier> seen = new HashSet<>();
        seen.add(id);
        LegacyUnbakedModels.markDependencies(claimed, resolver, claims, seen);
    }

    @Override
    public BlockStateModel bake(ModelBaker baker) {
        return LegacyUnbakedModels.bakeById(new LegacyClaimingBaker(baker, claims), id, BlockModelRotation.IDENTITY);
    }
}
