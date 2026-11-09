/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.placement;

import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/** The placement filter base class mods extended before 26.3: it keeps or drops the one position it is given. */
public abstract class LegacyPlacementFilter extends LegacyPlacementModifier {

    public LegacyPlacementFilter() {
    }

    /** Whether {@code origin} passes, as the mod's own code decides. */
    protected abstract boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos origin);

    @Override
    public final Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin) {
        return shouldPlace(context, random, origin) ? Stream.of(origin) : Stream.empty();
    }
}
