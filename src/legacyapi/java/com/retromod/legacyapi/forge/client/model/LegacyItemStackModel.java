/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemModelHandle;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** The Forge BakedModel ItemRenderer.getModel hands out for a stack whose item model Minecraft draws itself. */
public final class LegacyItemStackModel implements BakedModel {

    private final LegacyItemModelHandle handle;

    public LegacyItemStackModel(LegacyItemModelHandle handle) {
        this.handle = handle;
    }

    /** The loader-neutral model this one answers through. */
    public LegacyItemModelHandle handle() {
        return handle;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return handle.getQuads(state, side, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return handle.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return handle.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return handle.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return handle.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return handle.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return handle.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean leftHand) {
        LegacyItemModelHandle shown = handle.applyTransform(context, poseStack, leftHand);
        return shown == handle ? this : new LegacyItemStackModel(shown);
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        return handle.getRenderTypes(stack, fabulous);
    }

    @Override
    public String toString() {
        return "LegacyItemStackModel[" + handle + "]";
    }
}
