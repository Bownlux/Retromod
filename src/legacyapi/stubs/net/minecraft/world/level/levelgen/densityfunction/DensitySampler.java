// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.densityfunction;

public interface DensitySampler {
    void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume);

    static void sampleVolumeNaive(SamplerContext context, DensityBuffer buffer, DensityVolume volume, DensitySampler sampler) {
        throw new UnsupportedOperationException();
    }

    float sampleValue(SamplerContext context, int x, int y, int z);

    final class Bound {
        public float sampleValue(int x, int y, int z) { throw new UnsupportedOperationException(); }
    }
}
