// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.densityfunction;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.util.Interval;

public final class DensityFunctions {
    public static DensityFunction constant(float value) { throw new UnsupportedOperationException(); }

    public static DensityFunction yClampedGradient(int fromY, int toY, float fromValue, float toValue) {
        throw new UnsupportedOperationException();
    }

    public static DensityFunction interpolated(DensityFunction input, int cellSizeXz, int cellSizeY) {
        throw new UnsupportedOperationException();
    }

    public static final class HolderHolder implements DensityFunction {
        public HolderHolder(Holder<DensityFunction> function) { throw new UnsupportedOperationException(); }
        public Holder<DensityFunction> function() { throw new UnsupportedOperationException(); }
        public DensitySampler compileSampler(CompileContext context) { throw new UnsupportedOperationException(); }
        public DensityFunction rewriteChildren(DfRewriteRule rule) { throw new UnsupportedOperationException(); }
        public Interval range() { throw new UnsupportedOperationException(); }
        public int domainAxes() { throw new UnsupportedOperationException(); }
        public MapCodec<HolderHolder> codec() { throw new UnsupportedOperationException(); }
    }
}
