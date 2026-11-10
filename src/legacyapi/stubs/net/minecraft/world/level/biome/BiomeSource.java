// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.biome;

public abstract class BiomeSource {
    public abstract BiomeResolver createResolver(Climate.Sampler sampler);
}
