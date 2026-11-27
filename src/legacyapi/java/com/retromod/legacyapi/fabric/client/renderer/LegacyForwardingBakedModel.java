/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import com.retromod.legacyapi.minecraft.client.renderer.LegacyBakedModelCalls;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemOverrides;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric API's old {@code ForwardingBakedModel}: everything goes to the wrapped model unless a subclass draws differently. */
public abstract class LegacyForwardingBakedModel implements LegacyFabricBakedModel {

    /** The model this one wraps, set by the subclass. */
    protected BlockStateModel wrapped;

    // Whether a subclass replaced the old getQuads, after which the 26.x members must come from its quads.
    private static final ClassValue<Boolean> OWN_QUADS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getQuads", BlockState.class, Direction.class, RandomSource.class).getDeclaringClass()
                        != LegacyForwardingBakedModel.class;
            } catch (NoSuchMethodException | LinkageError unknown) {
                return true;
            }
        }
    };

    protected LegacyForwardingBakedModel() {}

    @Override
    public boolean isVanillaAdapter() {
        return LegacyFabricModels.isVanillaAdapter(wrapped);
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, Supplier<RandomSource> random,
            QuadEmitter context) {
        LegacyFabricModels.emitBlockQuads(wrapped, level, state, pos, random, context);
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<RandomSource> random, QuadEmitter context) {
        LegacyFabricModels.emitItemQuads(wrapped, stack, random, context);
    }

    // The old BakedModel members, forwarded through Retromod's calls on 26.x's block model.

    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return LegacyBakedModelCalls.getQuads(wrapped, state, side, random);
    }

    public boolean useAmbientOcclusion() {
        return LegacyBakedModelCalls.useAmbientOcclusion(wrapped);
    }

    public boolean isGui3d() {
        return LegacyBakedModelCalls.isGui3d(wrapped);
    }

    public boolean usesBlockLight() {
        return LegacyBakedModelCalls.usesBlockLight(wrapped);
    }

    public boolean isCustomRenderer() {
        return LegacyBakedModelCalls.isCustomRenderer(wrapped);
    }

    public TextureAtlasSprite getParticleIcon() {
        return LegacyBakedModelCalls.getParticleIcon(wrapped);
    }

    public ItemTransforms getTransforms() {
        return LegacyBakedModelCalls.getTransforms(wrapped);
    }

    public LegacyItemOverrides getOverrides() {
        return LegacyBakedModelCalls.getOverrides(wrapped);
    }

    public BlockStateModel getWrappedModel() {
        return wrapped;
    }

    // The 26.x members: a subclass that kept the old drawing draws exactly as the wrapped model does.

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            Predicate<Direction> cullTest) {
        if (wrapped != null && isVanillaAdapter() && !OWN_QUADS.get(getClass())) {
            ((FabricBlockStateModel) wrapped).emitQuads(emitter, level, pos, state, random, cullTest);
        } else {
            LegacyFabricBakedModel.super.emitQuads(emitter, level, pos, state, random, cullTest);
        }
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        if (wrapped != null && !OWN_QUADS.get(getClass())) wrapped.collectParts(random, parts);
        else LegacyFabricBakedModel.super.collectParts(random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
        return wrapped != null ? wrapped.particleMaterial() : LegacyFabricBakedModel.super.particleMaterial();
    }

    @Override
    public int materialFlags() {
        return wrapped != null && !OWN_QUADS.get(getClass()) ? wrapped.materialFlags() : LegacyFabricBakedModel.super.materialFlags();
    }
}
