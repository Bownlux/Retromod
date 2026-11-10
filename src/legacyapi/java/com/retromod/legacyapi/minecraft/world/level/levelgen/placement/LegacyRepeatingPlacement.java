/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.placement;

import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/** The repeating placement base class mods extended before 26.3: the same position, as many times as the mod counts. */
public abstract class LegacyRepeatingPlacement extends LegacyPlacementModifier {

    public LegacyRepeatingPlacement() {
    }

    /** How many times {@code origin} repeats, as the mod's own code counts it. */
    protected abstract int count(RandomSource random, BlockPos origin);

    @Override
    public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin) {
        int times = count(random, origin);
        Stream.Builder<BlockPos> positions = Stream.builder();
        for (int i = 0; i < times; i++) positions.add(origin);
        return positions.build();
    }
}
