/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

/** Minecraft 1.20.1's BakedModel with Forge's IForgeBakedModel members, which Minecraft 26.2 replaced with block and item models. */
public interface BakedModel {

    List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random);

    boolean useAmbientOcclusion();

    boolean isGui3d();

    boolean usesBlockLight();

    boolean isCustomRenderer();

    TextureAtlasSprite getParticleIcon();

    default ItemTransforms getTransforms() {
        return ItemTransforms.NO_TRANSFORMS;
    }

    ItemOverrides getOverrides();

    // ---- Forge 1.20.1's IForgeBakedModel ----

    default List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
        return getQuads(state, side, random);
    }

    default boolean useAmbientOcclusion(BlockState state) {
        return useAmbientOcclusion();
    }

    default boolean useAmbientOcclusion(BlockState state, RenderType renderType) {
        return useAmbientOcclusion(state);
    }

    /** Applies the display transform to the pose and answers the model to draw, as Forge 1.20.1's item renderer asked. */
    default BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean leftHand) {
        getTransforms().getTransform(context).apply(leftHand, poseStack.last());
        // Minecraft 26.2's transform also centres the item, which Forge 1.20.1's item renderer did after this call.
        poseStack.translate(0.5f, 0.5f, 0.5f);
        return this;
    }

    default ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        return data;
    }

    default TextureAtlasSprite getParticleIcon(ModelData data) {
        return getParticleIcon();
    }

    /** The chunk layers the model draws in: an old setRenderLayer choice, else the layers of the block's own model. */
    default ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return LegacyBakedModels.defaultRenderTypes(state);
    }

    default List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        return LegacyBakedModels.defaultItemRenderTypes();
    }

    default List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return List.of(this);
    }
}
