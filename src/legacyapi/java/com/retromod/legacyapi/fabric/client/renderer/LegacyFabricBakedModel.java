/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** An old mod's baked model as a 26.x block model, drawn through its old emit methods. */
public interface LegacyFabricBakedModel extends BlockStateModel, FabricBlockStateModel {

    /** Old FabricBakedModel default: a model that never overrode it draws through its quads. */
    default boolean isVanillaAdapter() {
        return true;
    }

    /** Old FabricBakedModel default: the model's quads for this block. */
    default void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, Supplier<RandomSource> random,
            QuadEmitter context) {
        LegacyFabricModels.emitVanillaBlock(this, context, state, random);
    }

    /** Old FabricBakedModel default: the model's quads as an item. */
    default void emitItemQuads(ItemStack stack, Supplier<RandomSource> random, QuadEmitter context) {
        LegacyFabricModels.emitVanillaItem(this, context, random);
    }

    @Override
    default void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            Predicate<Direction> cullTest) {
        // A model whose old code failed once draws the host's way from here on, from its quads where those still work.
        if (!LegacyFabricModels.emitBlock(this, emitter, level, pos, state, random, cullTest)) {
            FabricBlockStateModel.super.emitQuads(emitter, level, pos, state, random, cullTest);
        }
    }

    @Override
    default void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        LegacyFabricModels.collectParts(this, random, parts);
    }

    @Override
    default Material.Baked particleMaterial() {
        return LegacyFabricModels.particleMaterial(this);
    }

    @Override
    default int materialFlags() {
        return LegacyFabricModels.materialFlags(this);
    }
}
