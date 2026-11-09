/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import com.retromod.legacyapi.minecraft.client.model.LegacyChunkLayers;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyBakedModelCalls;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemOverrides;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyQuadDraws;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.TriState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.model.data.ModelData;

/** NeoForge 1.21.1's and Forge 1.20.1's IDynamicBakedModel on NeoForge 26.x, where an old BakedModel is Minecraft's block model. */
public interface IDynamicBakedModel extends BlockStateModel {

    // ---- Minecraft 1.21.1's BakedModel, which every old model implements ----

    default List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return getQuads(state, side, random, ModelData.EMPTY, null);
    }

    List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType);

    boolean useAmbientOcclusion();

    boolean isGui3d();

    boolean usesBlockLight();

    boolean isCustomRenderer();

    TextureAtlasSprite getParticleIcon();

    ItemTransforms getTransforms();

    LegacyItemOverrides getOverrides();

    // ---- NeoForge 1.21.1's IBakedModelExtension and Forge 1.20.1's IForgeBakedModel, with their default answers ----

    default TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
        return useAmbientOcclusion() ? TriState.DEFAULT : TriState.FALSE;
    }

    default boolean useAmbientOcclusion(BlockState state) {
        return useAmbientOcclusion();
    }

    default boolean useAmbientOcclusion(BlockState state, RenderType renderType) {
        return useAmbientOcclusion(state);
    }

    /** Applies the display transform to the pose and answers the model to draw, as the old item renderer asked. */
    default BlockStateModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
        ItemTransforms transforms = getTransforms();
        if (transforms != null) transforms.getTransform(context).apply(leftHand, pose.last());
        // Minecraft 26.x's transform also centres the item, which the old item renderer did after this call.
        pose.translate(0.5f, 0.5f, 0.5f);
        return this;
    }

    default ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        return data;
    }

    default TextureAtlasSprite getParticleIcon(ModelData data) {
        return getParticleIcon();
    }

    /** The chunk layers the model draws in: an old setRenderLayer choice, else the layer of the block's own model. */
    default ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return LegacyChunkLayers.getRenderLayers(state);
    }

    /** The item render types its quads draw with, which Minecraft 26.x reads from each quad's texture. */
    default List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        return LegacyQuadDraws.itemRenderTypes(LegacyBakedModelCalls.allQuads(this));
    }

    default List<BlockStateModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return List.of(this);
    }

    // ---- Minecraft 26.x's block model, drawn through the members above ----

    @Override
    default void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        LegacyModelDataCalls.collectParts(this, null, null, null, random, parts);
    }

    /** NeoForge 26.x's per-block parts, which hand the block entity's model data to the old members. */
    default void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        LegacyModelDataCalls.collectParts(this, level, pos, state, random, parts);
    }

    @Override
    default Material.Baked particleMaterial() {
        return LegacyModelDataCalls.particleMaterial(this, null, null, null);
    }

    default Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return LegacyModelDataCalls.particleMaterial(this, level, pos, state);
    }

    @Override
    default int materialFlags() {
        return LegacyModelDataCalls.materialFlags(this, null);
    }

    default int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return LegacyModelDataCalls.materialFlags(this, state);
    }
}
