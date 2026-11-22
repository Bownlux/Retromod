/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature.configurations;

import com.mojang.datafixers.kinds.App;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.FloatProviders;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** The large dripstone settings 26.3 folded into its own feature record, kept for a mod's feature that reads them. */
public class LegacyLargeDripstoneConfiguration implements FeatureConfiguration {

    public static final Codec<LegacyLargeDripstoneConfiguration> CODEC = RecordCodecBuilder.create(LegacyLargeDripstoneConfiguration::layout);

    public final int floorToCeilingSearchRange;
    public final IntProvider columnRadius;
    public final FloatProvider heightScale;
    public final float maxColumnRadiusToCaveHeightRatio;
    public final FloatProvider stalactiteBluntness;
    public final FloatProvider stalagmiteBluntness;
    public final FloatProvider windSpeed;
    public final int minRadiusForWind;
    public final float minBluntnessForWind;

    public LegacyLargeDripstoneConfiguration(int floorToCeilingSearchRange, IntProvider columnRadius, FloatProvider heightScale,
            float maxColumnRadiusToCaveHeightRatio, FloatProvider stalactiteBluntness, FloatProvider stalagmiteBluntness,
            FloatProvider windSpeed, int minRadiusForWind, float minBluntnessForWind) {
        this.floorToCeilingSearchRange = floorToCeilingSearchRange;
        this.columnRadius = columnRadius;
        this.heightScale = heightScale;
        this.maxColumnRadiusToCaveHeightRatio = maxColumnRadiusToCaveHeightRatio;
        this.stalactiteBluntness = stalactiteBluntness;
        this.stalagmiteBluntness = stalagmiteBluntness;
        this.windSpeed = windSpeed;
        this.minRadiusForWind = minRadiusForWind;
        this.minBluntnessForWind = minBluntnessForWind;
    }

    /** The keys old data files use, every one required. */
    private static App<RecordCodecBuilder.Mu<LegacyLargeDripstoneConfiguration>, LegacyLargeDripstoneConfiguration> layout(
            RecordCodecBuilder.Instance<LegacyLargeDripstoneConfiguration> fields) {
        return fields.group(
                Codec.INT.fieldOf("floor_to_ceiling_search_range").forGetter(c -> c.floorToCeilingSearchRange),
                IntProviders.CODEC.fieldOf("column_radius").forGetter(c -> c.columnRadius),
                FloatProviders.CODEC.fieldOf("height_scale").forGetter(c -> c.heightScale),
                Codec.FLOAT.fieldOf("max_column_radius_to_cave_height_ratio").forGetter(c -> c.maxColumnRadiusToCaveHeightRatio),
                FloatProviders.CODEC.fieldOf("stalactite_bluntness").forGetter(c -> c.stalactiteBluntness),
                FloatProviders.CODEC.fieldOf("stalagmite_bluntness").forGetter(c -> c.stalagmiteBluntness),
                FloatProviders.CODEC.fieldOf("wind_speed").forGetter(c -> c.windSpeed),
                Codec.INT.fieldOf("min_radius_for_wind").forGetter(c -> c.minRadiusForWind),
                Codec.FLOAT.fieldOf("min_bluntness_for_wind").forGetter(c -> c.minBluntnessForWind))
                .apply(fields, LegacyLargeDripstoneConfiguration::new);
    }
}
