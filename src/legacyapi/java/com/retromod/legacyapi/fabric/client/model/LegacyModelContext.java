/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;

/** The context every old model loading callback received, for a model file id or a block state's top-level location. */
final class LegacyModelContext implements LegacyModelResolver.Context, LegacyBeforeBake.Context, LegacyAfterBake.Context,
        LegacyModelProviders.ModelProviderContext, ModelModifier.OnLoad.Context {

    final LegacyModelClaims claims;
    private final Identifier resourceId;
    private final Identifier topLevelId;
    private final UnbakedModel sourceModel;
    private final ModelBaker baker;

    LegacyModelContext(LegacyModelClaims claims, Identifier resourceId, Identifier topLevelId) {
        this(claims, resourceId, topLevelId, null, null);
    }

    LegacyModelContext(LegacyModelClaims claims, Identifier resourceId, Identifier topLevelId, UnbakedModel sourceModel,
            ModelBaker baker) {
        this.claims = claims;
        this.resourceId = resourceId;
        this.topLevelId = topLevelId;
        this.sourceModel = sourceModel;
        this.baker = baker == null || baker instanceof LegacyClaimingBaker ? baker : new LegacyClaimingBaker(baker, claims);
    }

    // Fabric API 1.20.x named a block state's top-level model by its location, where 1.21 split the id in two.
    @Override
    public Identifier id() {
        return resourceId != null ? resourceId : topLevelId;
    }

    @Override
    public Identifier resourceId() {
        return resourceId;
    }

    @Override
    public Identifier topLevelId() {
        return topLevelId;
    }

    @Override
    public UnbakedModel getOrLoadModel(Identifier id) {
        return LegacyUnbakedModels.load(claims, id);
    }

    @Override
    public UnbakedModel loadModel(Identifier id) {
        return LegacyUnbakedModels.load(claims, id);
    }

    @Override
    public ModelBakery loader() {
        // 26.x bakes from an object old code cannot read; old code reaches models through the context instead.
        return null;
    }

    @Override
    public Function<SpriteId, TextureAtlasSprite> textureGetter() {
        return baker == null ? null : LegacyUnbakedModels.sprites(baker, resourceId != null ? resourceId : topLevelId);
    }

    @Override
    public ModelState settings() {
        return BlockModelRotation.IDENTITY;
    }

    @Override
    public ModelBaker baker() {
        return baker;
    }

    @Override
    public UnbakedModel sourceModel() {
        return sourceModel;
    }
}
