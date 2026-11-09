/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature.configurations;

import com.mojang.datafixers.kinds.App;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * The patch settings 26.1 removed, kept for a mod's own patch feature: how many placements to
 * try, how far apart, and which placed feature each one places.
 */
public final class RandomPatchConfiguration implements FeatureConfiguration {

    // The data format old mods wrote: these keys, and these values when a key is left out.
    private static final String TRIES_KEY = "tries";
    private static final String XZ_SPREAD_KEY = "xz_spread";
    private static final String Y_SPREAD_KEY = "y_spread";
    private static final String FEATURE_KEY = "feature";
    private static final int TRIES_WHEN_ABSENT = 128;
    private static final int XZ_SPREAD_WHEN_ABSENT = 7;
    private static final int Y_SPREAD_WHEN_ABSENT = 3;

    public static final Codec<RandomPatchConfiguration> CODEC = RecordCodecBuilder.create(RandomPatchConfiguration::layout);

    private final int tries;
    private final int xzSpread;
    private final int ySpread;
    private final Holder<PlacedFeature> feature;

    public RandomPatchConfiguration(int tries, int xzSpread, int ySpread, Holder<PlacedFeature> feature) {
        this.tries = tries;
        this.xzSpread = xzSpread;
        this.ySpread = ySpread;
        this.feature = feature;
    }

    public int tries() {
        return tries;
    }

    public int xzSpread() {
        return xzSpread;
    }

    public int ySpread() {
        return ySpread;
    }

    public Holder<PlacedFeature> feature() {
        return feature;
    }

    /** The four fields: a positive try count, two spreads that may be zero, and the placed feature. */
    private static App<RecordCodecBuilder.Mu<RandomPatchConfiguration>, RandomPatchConfiguration> layout(
            RecordCodecBuilder.Instance<RandomPatchConfiguration> fields) {
        MapCodec<Integer> tryCount = ExtraCodecs.POSITIVE_INT.fieldOf(TRIES_KEY).orElse(TRIES_WHEN_ABSENT);
        return fields.group(
                tryCount.forGetter(RandomPatchConfiguration::tries),
                spread(XZ_SPREAD_KEY, XZ_SPREAD_WHEN_ABSENT).forGetter(RandomPatchConfiguration::xzSpread),
                spread(Y_SPREAD_KEY, Y_SPREAD_WHEN_ABSENT).forGetter(RandomPatchConfiguration::ySpread),
                PlacedFeature.CODEC.fieldOf(FEATURE_KEY).forGetter(RandomPatchConfiguration::feature))
                .apply(fields, RandomPatchConfiguration::new);
    }

    private static MapCodec<Integer> spread(String key, int whenAbsent) {
        return ExtraCodecs.NON_NEGATIVE_INT.fieldOf(key).orElse(whenAbsent);
    }
}
