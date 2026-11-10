/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyBakedModelCalls;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemOverrides;
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

/** NeoForge 1.21.1's and Forge 1.20.1's BakedModelWrapper on NeoForge 26.x: every member answered by the wrapped model, for subclasses to change some. */
public abstract class BakedModelWrapper<T extends BlockStateModel> implements BlockStateModel {

    protected final T originalModel;

    public BakedModelWrapper(T originalModel) {
        this.originalModel = originalModel;
    }

    // The wrapped model may be Minecraft's, an old mod's, or another jar's, so each answer goes through the old-call helpers.

    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return LegacyBakedModelCalls.getQuads(originalModel, state, side, random);
    }

    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
        return LegacyModelDataCalls.getQuads(originalModel, state, side, random, data, renderType);
    }

    public boolean useAmbientOcclusion() {
        return LegacyBakedModelCalls.useAmbientOcclusion(originalModel);
    }

    public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
        return LegacyModelDataCalls.useAmbientOcclusion(originalModel, state, data, renderType);
    }

    public boolean useAmbientOcclusion(BlockState state) {
        return LegacyModelDataCalls.useAmbientOcclusion(originalModel, state);
    }

    public boolean useAmbientOcclusion(BlockState state, RenderType renderType) {
        return LegacyModelDataCalls.useAmbientOcclusion(originalModel, state, renderType);
    }

    public boolean isGui3d() {
        return LegacyBakedModelCalls.isGui3d(originalModel);
    }

    public boolean usesBlockLight() {
        return LegacyBakedModelCalls.usesBlockLight(originalModel);
    }

    public boolean isCustomRenderer() {
        return LegacyBakedModelCalls.isCustomRenderer(originalModel);
    }

    public TextureAtlasSprite getParticleIcon() {
        return LegacyBakedModelCalls.getParticleIcon(originalModel);
    }

    public TextureAtlasSprite getParticleIcon(ModelData data) {
        return LegacyModelDataCalls.getParticleIcon(originalModel, data);
    }

    public ItemTransforms getTransforms() {
        return LegacyBakedModelCalls.getTransforms(originalModel);
    }

    public LegacyItemOverrides getOverrides() {
        return LegacyBakedModelCalls.getOverrides(originalModel);
    }

    // The old wrapper answered with the wrapped model's own result, so a subclass that wants itself drawn overrides this.
    public BlockStateModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
        return LegacyBakedModelCalls.applyTransform(originalModel, context, pose, leftHand);
    }

    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        return LegacyModelDataCalls.getModelData(originalModel, level, pos, state, data);
    }

    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return LegacyModelDataCalls.getRenderTypes(originalModel, state, random, data);
    }

    public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        return LegacyBakedModelCalls.getRenderTypes(originalModel, stack, fabulous);
    }

    public List<BlockStateModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return LegacyBakedModelCalls.getRenderPasses(originalModel, stack, fabulous);
    }

    // ---- Minecraft 26.x's block model, drawn through this wrapper's members, a subclass's changes included ----

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        LegacyModelDataCalls.collectParts(this, null, null, null, random, parts);
    }

    /** NeoForge 26.x's per-block parts, which hand the block entity's model data to the old members. */
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        LegacyModelDataCalls.collectParts(this, level, pos, state, random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
        return LegacyModelDataCalls.particleMaterial(this, null, null, null);
    }

    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return LegacyModelDataCalls.particleMaterial(this, level, pos, state);
    }

    @Override
    public int materialFlags() {
        return LegacyModelDataCalls.materialFlags(this, null);
    }

    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return LegacyModelDataCalls.materialFlags(this, state);
    }
}
