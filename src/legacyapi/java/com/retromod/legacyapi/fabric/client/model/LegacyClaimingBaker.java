/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;

/** 26.x's model baker, except that an id an old supplier claimed resolves to that old model. */
final class LegacyClaimingBaker implements ModelBaker {
    final ModelBaker host;
    final LegacyModelClaims claims;

    LegacyClaimingBaker(ModelBaker host, LegacyModelClaims claims) {
        this.host = host instanceof LegacyClaimingBaker claiming ? claiming.host : host;
        this.claims = claims;
    }

    @Override
    public ResolvedModel getModel(Identifier id) {
        UnbakedModel claimed = claims.claim(id);
        return claimed != null ? claims.resolved(id, claimed) : host.getModel(id);
    }

    @Override
    public BlockStateModelPart missingBlockModelPart() {
        return host.missingBlockModelPart();
    }

    @Override
    public MaterialBaker materials() {
        return host.materials();
    }

    @Override
    public Interner interner() {
        return host.interner();
    }

    @Override
    public <T> T compute(SharedOperationKey<T> key) {
        // Computed here, uncached, so the work inside still sees the claims.
        return key.compute(this);
    }
}
