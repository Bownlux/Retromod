/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** The biome source mods extended before 26.3, answering 26.3's resolver from the source's old biome lookup. */
public abstract class LegacyBiomeSource extends BiomeSource {

    protected LegacyBiomeSource() {}

    /** The lookup a source implemented before 26.3. */
    public abstract Holder<?> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler);

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        // A source built for 26.3 overrides this; an old one answers each lookup with the sampler it was given.
        return (x, y, z) -> getNoiseBiome(x, y, z, sampler);
    }
}
