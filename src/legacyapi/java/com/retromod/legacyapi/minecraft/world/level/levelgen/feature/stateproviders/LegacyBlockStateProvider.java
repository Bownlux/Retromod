/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature.stateproviders;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/** The block state provider base class mods extended before 26.3 made it an interface. */
public abstract class LegacyBlockStateProvider implements BlockStateProvider {

    public LegacyBlockStateProvider() {
    }

    /** The mod's registered type. Old types are rebuilt as the codecs 26.3 registers, so this is one. */
    protected abstract LegacyBlockStateProviderType<?> type();

    /** The state for {@code pos}, as the mod's own code picks it, without the level the old method never had. */
    public abstract BlockState getState(RandomSource random, BlockPos pos);

    @Override
    public BlockState getState(LevelAccessor level, RandomSource random, BlockPos pos) {
        return getState(random, pos);
    }

    @Override
    public MapCodec<? extends BlockStateProvider> codec() {
        return type();
    }
}
