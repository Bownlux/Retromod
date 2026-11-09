// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.biome;

import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

public class Climate {
    public static final class Sampler {
        public DensitySampler.Bound temperature() { throw new UnsupportedOperationException(); }
        public DensitySampler.Bound humidity() { throw new UnsupportedOperationException(); }
        public DensitySampler.Bound continentalness() { throw new UnsupportedOperationException(); }
        public DensitySampler.Bound erosion() { throw new UnsupportedOperationException(); }
        public DensitySampler.Bound depth() { throw new UnsupportedOperationException(); }
        public DensitySampler.Bound weirdness() { throw new UnsupportedOperationException(); }
    }
}
