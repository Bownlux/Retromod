/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature;

import com.mojang.serialization.Codec;
import com.retromod.generated.LegacyFeature;
import com.retromod.legacyapi.minecraft.world.level.levelgen.feature.configurations.LegacyTreeConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** The old tree feature base 26.3 replaced with a record: a mod's tree class places the blocks, this base does the rest. */
public abstract class LegacyTreeFeature extends LegacyFeature {

    // Notify neighbors and clients, and skip shape updates, as tree placement always has.
    private static final int FLAGS = 19;
    // Leaves further than this from a log decay.
    private static final int MAX_DISTANCE = 7;

    protected LegacyTreeFeature(Codec<?> configCodec) {
        super(configCodec);
    }

    /** The mod's tree shape: it places roots, trunk and leaves through the three setters. */
    protected abstract boolean doPlace(WorldGenLevel level, RandomSource random, BlockPos origin,
            BiConsumer<BlockPos, BlockState> rootSetter, BiConsumer<BlockPos, BlockState> trunkSetter,
            FoliagePlacer.FoliageSetter foliageSetter, LegacyTreeConfiguration config);

    // The old static checks, which subclasses call on themselves; 26.3's tree record still has each one.
    public static boolean isVine(LevelSimulatedReader level, BlockPos pos) {
        return TreeFeature.isVine(level, pos);
    }

    public static boolean isAirOrLeaves(LevelSimulatedReader level, BlockPos pos) {
        return TreeFeature.isAirOrLeaves(level, pos);
    }

    public static boolean validTreePos(LevelSimulatedReader level, BlockPos pos) {
        return TreeFeature.validTreePos(level, pos);
    }

    @Override
    public final boolean place(FeaturePlaceContext context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        LegacyTreeConfiguration config = (LegacyTreeConfiguration) context.config();
        Set<BlockPos> roots = new HashSet<>();
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> leaves = new HashSet<>();

        // Each setter writes the block and remembers where, for the decorators and the leaf distances.
        BiConsumer<BlockPos, BlockState> rootSetter = recording(level, roots);
        BiConsumer<BlockPos, BlockState> trunkSetter = recording(level, logs);
        BiConsumer<BlockPos, BlockState> leafWriter = recording(level, leaves);
        FoliagePlacer.FoliageSetter foliageSetter = new FoliagePlacer.FoliageSetter() {
            @Override
            public void set(BlockPos pos, BlockState state) {
                leafWriter.accept(pos, state);
            }

            @Override
            public boolean isSet(BlockPos pos) {
                return leaves.contains(pos);
            }
        };
        if (!doPlace(level, random, context.origin(), rootSetter, trunkSetter, foliageSetter, config)) return false;
        if (logs.isEmpty() && leaves.isEmpty()) return false;

        // Decorators (beehives, vines, cocoa) read the finished tree.
        if (!config.decorators.isEmpty()) {
            TreeDecorator.Context decorating = new TreeDecorator.Context(level, recording(level, new HashSet<>()), random,
                    logs, leaves, roots);
            for (TreeDecorator decorator : config.decorators) decorator.place(decorating);
        }
        setLeafDistances(level, logs, leaves);
        return true;
    }

    private static BiConsumer<BlockPos, BlockState> recording(WorldGenLevel level, Set<BlockPos> placed) {
        return (pos, state) -> {
            placed.add(pos.immutable());
            level.setBlock(pos, state, FLAGS);
        };
    }

    /** Walks out from the logs through this tree's own leaves, so each leaf knows how far its log is and does not decay. */
    private static void setLeafDistances(WorldGenLevel level, Set<BlockPos> logs, Set<BlockPos> leaves) {
        Map<BlockPos, Integer> distance = new HashMap<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        for (BlockPos log : logs) {
            distance.put(log, 0);
            frontier.add(log);
        }
        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.poll();
            int next = distance.get(pos) + 1;
            if (next >= MAX_DISTANCE) continue;
            for (Direction side : Direction.values()) {
                BlockPos neighbor = pos.relative(side);
                if (!leaves.contains(neighbor) || distance.containsKey(neighbor)) continue;
                distance.put(neighbor, next);
                frontier.add(neighbor);
            }
        }
        for (BlockPos leaf : leaves) {
            Integer steps = distance.get(leaf);
            BlockState state = level.getBlockState(leaf);
            if (steps == null || !state.hasProperty(BlockStateProperties.DISTANCE)) continue;
            level.setBlock(leaf, (BlockState) state.setValue(BlockStateProperties.DISTANCE, steps), FLAGS);
        }
    }
}
