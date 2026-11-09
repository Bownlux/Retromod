/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature.stateproviders;

import com.mojang.serialization.MapCodec;
import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyTypeCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/** The old block state provider calls 26.3 removed: building a type from a codec, and asking a provider without a level. */
public final class LegacyBlockStateProviders {

    private LegacyBlockStateProviders() {
    }

    /** {@code new BlockStateProviderType(CODEC)}: the codec 26.3 registers in the type's place. */
    public static LegacyBlockStateProviderType<BlockStateProvider> type(Object codec) {
        return new LegacyBlockStateProviderType<>(() -> codec);
    }

    /** {@code type.codec()}: the type is its own codec now. */
    public static MapCodec<?> codec(Object type) {
        return LegacyTypeCodec.codecOf(type);
    }

    /** {@code provider.getState(random, pos)}, written before the level was passed. */
    public static BlockState getState(BlockStateProvider provider, RandomSource random, BlockPos pos) {
        if (provider instanceof LegacyBlockStateProvider old) return old.getState(random, pos);
        // The old call had no level to pass. Most host providers never read it; one that does fails here,
        // as the old call would have failed on this host anyway.
        return provider.getState(null, random, pos);
    }
}
