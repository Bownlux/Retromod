/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature.configurations;

import com.mojang.datafixers.kinds.App;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/** The lake settings 26.3 folded into its lake record: the fluid inside and the barrier around it. */
public class LegacyLakeConfiguration implements FeatureConfiguration {

    public static final Codec<LegacyLakeConfiguration> CODEC = RecordCodecBuilder.create(LegacyLakeConfiguration::layout);

    private final BlockStateProvider fluid;
    private final BlockStateProvider barrier;

    public LegacyLakeConfiguration(BlockStateProvider fluid, BlockStateProvider barrier) {
        this.fluid = fluid;
        this.barrier = barrier;
    }

    public BlockStateProvider fluid() {
        return fluid;
    }

    public BlockStateProvider barrier() {
        return barrier;
    }

    private static App<RecordCodecBuilder.Mu<LegacyLakeConfiguration>, LegacyLakeConfiguration> layout(
            RecordCodecBuilder.Instance<LegacyLakeConfiguration> fields) {
        return fields.group(
                BlockStateProvider.DIRECT_CODEC.fieldOf("fluid").forGetter(LegacyLakeConfiguration::fluid),
                BlockStateProvider.DIRECT_CODEC.fieldOf("barrier").forGetter(LegacyLakeConfiguration::barrier))
                .apply(fields, LegacyLakeConfiguration::new);
    }
}
