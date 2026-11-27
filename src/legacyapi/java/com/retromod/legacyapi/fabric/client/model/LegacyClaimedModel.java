/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/** An id an old supplier claimed, as 26.x's baking resolves it: its geometry is the old model's quads, baked on demand. */
final class LegacyClaimedModel implements ResolvedModel {
    private final UnbakedModel old;
    private final Identifier id;
    private final LegacyModelClaims claims;
    private final Map<ModelState, BakeResult> baked = new ConcurrentHashMap<>();

    LegacyClaimedModel(UnbakedModel old, Identifier id, LegacyModelClaims claims) {
        this.old = old;
        this.id = id;
        this.claims = claims;
    }

    /** The host unbaked model standing for an old one, for a model file an old modifier replaced. */
    static UnbakedModel unbaked(UnbakedModel old, Identifier id, LegacyModelClaims claims) {
        return new Unbaked(new LegacyClaimedModel(old, id, claims));
    }

    @Override
    public UnbakedModel wrapped() {
        return new Unbaked(this);
    }

    @Override
    public ResolvedModel parent() {
        return null;
    }

    @Override
    public String debugName() {
        return id.toString();
    }

    @Override
    public TextureSlots getTopTextureSlots() {
        return TextureSlots.EMPTY;
    }

    @Override
    public boolean getTopAmbientOcclusion() {
        return true;
    }

    @Override
    public UnbakedModel.GuiLight getTopGuiLight() {
        return UnbakedModel.GuiLight.SIDE;
    }

    @Override
    public UnbakedGeometry getTopGeometry() {
        return (slots, baker, state, name) -> bakeGeometry(baker, state);
    }

    @Override
    public QuadCollection bakeTopGeometry(TextureSlots slots, ModelBaker baker, ModelState state) {
        return bakeGeometry(baker, state);
    }

    @Override
    public Material.Baked resolveParticleMaterial(TextureSlots slots, ModelBaker baker) {
        BlockStateModel model = bake(baker, BlockModelRotation.IDENTITY);
        return model == null ? baker.missingBlockModelPart().particleMaterial() : model.particleMaterial();
    }

    @Override
    public ItemTransforms getTopTransforms() {
        return ItemTransforms.NO_TRANSFORMS;
    }

    /** The old model baked with {@code state}, once per state; null when it cannot bake. */
    BlockStateModel bake(ModelBaker baker, ModelState state) {
        ModelState settings = state == null ? BlockModelRotation.IDENTITY : state;
        BakeResult found = baked.computeIfAbsent(settings, unused -> new BakeResult(LegacyUnbakedModels.bakeOld(old, claiming(baker),
                LegacyUnbakedModels.sprites(baker, id), settings, id)));
        return found.model;
    }

    // The old model's quads as 26.x geometry, recorded so a block model wrapper can put the old model itself back.
    private QuadCollection bakeGeometry(ModelBaker baker, ModelState state) {
        BlockStateModel model = bake(baker, state);
        if (model == null) return QuadCollection.EMPTY;
        QuadCollection.Builder quads = new QuadCollection.Builder();
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        for (BlockStateModelPart part : parts) {
            for (Direction side : Direction.values()) {
                for (BakedQuad quad : part.getQuads(side)) quads.addCulledFace(side, quad);
            }
            for (BakedQuad quad : part.getQuads(null)) quads.addUnculledFace(quad);
        }
        QuadCollection built = quads.build();
        claims.recordBake(built, model);
        return built;
    }

    private ModelBaker claiming(ModelBaker baker) {
        return baker instanceof LegacyClaimingBaker ? baker : new LegacyClaimingBaker(baker, claims);
    }

    /** A bake result, which may be no model. */
    private static final class BakeResult {
        final BlockStateModel model;

        BakeResult(BlockStateModel model) {
            this.model = model;
        }
    }

    /** The unbaked side of a claimed model, which 26.x reads through its geometry. */
    static final class Unbaked implements UnbakedModel {
        final LegacyClaimedModel claimed;

        Unbaked(LegacyClaimedModel claimed) {
            this.claimed = claimed;
        }

        @Override
        public UnbakedGeometry geometry() {
            return claimed.getTopGeometry();
        }

        @Override
        public Boolean ambientOcclusion() {
            return Boolean.TRUE;
        }

        @Override
        public Identifier parent() {
            return null;
        }

        @Override
        public TextureSlots.Data textureSlots() {
            return TextureSlots.Data.EMPTY;
        }

        UnbakedModel old() {
            return claimed.old;
        }

        Identifier id() {
            return claimed.id;
        }
    }
}
