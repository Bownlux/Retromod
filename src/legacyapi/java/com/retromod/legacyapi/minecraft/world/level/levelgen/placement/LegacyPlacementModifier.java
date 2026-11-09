/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.placement;

import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** The placement modifier base class mods extended before 26.3 made it an interface. */
public abstract class LegacyPlacementModifier implements PlacementModifier {

    public LegacyPlacementModifier() {
    }

    /** The positions this modifier makes from {@code origin}, as the mod's own code returns them. */
    public abstract Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin);

    /** The mod's registered type. Old types are rebuilt as the codecs 26.3 registers, so this is one. */
    public abstract LegacyPlacementModifierType<?> type();

    @Override
    public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
        getPositions(context, random, origin).forEach(output);
    }

    @Override
    public MapCodec<? extends PlacementModifier> codec() {
        return type();
    }
}
