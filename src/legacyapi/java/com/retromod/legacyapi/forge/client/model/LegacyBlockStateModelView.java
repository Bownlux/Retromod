/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.IForgeBlockStateModel;
import net.minecraftforge.client.model.data.ModelData;

/** An old BakedModel that reads Minecraft 26.2's block model, as Forge 1.20.1's getModels() handed out. */
public final class LegacyBlockStateModelView implements BakedModel {

    // The last host parts built on this thread: host model, seed, model data, parts.
    private static final ThreadLocal<Object[]> LAST = new ThreadLocal<>();

    private final BlockStateModel host;

    public LegacyBlockStateModelView(BlockStateModel host) {
        this.host = host;
    }

    /** The host model this view reads. */
    public BlockStateModel host() {
        return host;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return getQuads(state, side, random, ModelData.EMPTY, null);
    }

    /** The host's quads for that side; with a render type, only the quads drawn in its chunk layer. */
    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
        ChunkSectionLayer layer = renderType == null ? null : ChunkRenderTypeSet.layerOf(renderType);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts(random, data)) {
            for (BakedQuad quad : part.getQuads(side)) {
                if (layer == null || quad.materialInfo().layer() == layer) quads.add(quad);
            }
        }
        return quads;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return ChunkRenderTypeSet.ofBits(LegacyModelMaps.layersOf(parts(random, data)));
    }

    @Override
    public boolean useAmbientOcclusion() {
        // Its own random, so these parts stay out of the ones kept for an adapter's sides.
        List<BlockStateModelPart> parts = new ArrayList<>();
        forge(host).collectParts(RandomSource.create(42L), parts, ModelData.EMPTY);
        return parts.isEmpty() || parts.get(0).useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return sprite(host.particleMaterial());
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        return sprite(forge(host).particleMaterial(data));
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        return forge(host).getModelData(level, pos, state, data);
    }

    private List<BlockStateModelPart> parts(RandomSource random, ModelData data) {
        ModelData given = data == null ? ModelData.EMPTY : data;
        // An adapter asks an old wrapper once per side with the same seed and data; the host's parts are kept for
        // those asks, so a wrapped model from another mod's copy is built once per layer, not once per side.
        long[] seed = LegacyBlockStateModel.currentSeed();
        Object[] last = seed == null ? null : LAST.get();
        if (last != null && last[0] == host && (Long) last[1] == seed[0] && last[2] == given) {
            @SuppressWarnings("unchecked")
            List<BlockStateModelPart> kept = (List<BlockStateModelPart>) last[3];
            return kept;
        }
        List<BlockStateModelPart> parts = new ArrayList<>();
        forge(host).collectParts(random == null ? RandomSource.create(42L) : random, parts, given);
        if (seed != null) LAST.set(new Object[]{host, seed[0], given, parts});
        return parts;
    }

    /** Drops the parts kept on this thread, once the adapter call they served has ended. */
    static void forgetParts() {
        LAST.remove();
    }

    /** The model's Forge members, which Forge's patched client declares through this extension. */
    static IForgeBlockStateModel forge(BlockStateModel model) {
        return model;
    }

    private static TextureAtlasSprite sprite(Material.Baked material) {
        return material == null ? null : material.sprite();
    }

    @Override
    public String toString() {
        return "LegacyBlockStateModelView[" + host + "]";
    }
}
