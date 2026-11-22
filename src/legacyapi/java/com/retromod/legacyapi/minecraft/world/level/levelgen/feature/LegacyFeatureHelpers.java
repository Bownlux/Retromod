/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.LevelWriter;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** The block helpers old feature classes inherited from Feature, which 26.3 turned into an interface without them. */
public final class LegacyFeatureHelpers {

    // Notify neighbors and clients, as the old setBlock did.
    private static final int UPDATE_ALL = 3;
    // Send to clients only, as the old safeSetBlock did.
    private static final int UPDATE_CLIENTS = 2;
    // The old helper marked at most this many blocks above for post-processing.
    private static final int POST_PROCESS_HEIGHT = 2;

    private LegacyFeatureHelpers() {}

    public static void setBlock(LevelWriter level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, UPDATE_ALL);
    }

    public static void safeSetBlock(WorldGenLevel level, BlockPos pos, BlockState state, Predicate<BlockState> canReplace) {
        if (canReplace.test(level.getBlockState(pos))) level.setBlock(pos, state, UPDATE_CLIENTS);
    }

    /** Marks the blocks right above for post-processing, stopping at the first air, as plants on top need it. */
    public static void markAboveForPostProcessing(WorldGenLevel level, BlockPos pos) {
        BlockPos above = pos;
        for (int step = 0; step < POST_PROCESS_HEIGHT; step++) {
            above = above.above();
            if (level.getBlockState(above).isAir()) return;
            level.getChunk(above).markPosForPostProcessing(above);
        }
    }

    public static boolean isStone(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    public static boolean isDirt(BlockState state) {
        return state.is(BlockTags.DIRT);
    }

    public static boolean isGrassOrDirt(LevelSimulatedReader level, BlockPos pos) {
        return level.isStateAtPosition(pos, LegacyFeatureHelpers::isDirt);
    }
}
