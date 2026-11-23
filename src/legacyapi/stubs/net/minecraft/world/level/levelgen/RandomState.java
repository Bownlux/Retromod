// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

public final class RandomState {
    public static RandomState create(net.minecraft.core.HolderGetter<?> noises, long seed, NoiseGeneratorSettings settings) { throw new UnsupportedOperationException(); }
    public Climate.Sampler createClimateSampler(SamplerContext context) { throw new UnsupportedOperationException(); }
    public PositionalRandomFactory getOrCreateRandomFactory(net.minecraft.resources.Identifier name) { throw new UnsupportedOperationException(); }
}
