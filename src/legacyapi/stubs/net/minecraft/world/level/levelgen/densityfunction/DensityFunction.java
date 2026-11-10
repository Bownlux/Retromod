// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.densityfunction;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public interface DensityFunction {
    int ALL_AXES = 7;

    DensitySampler compileSampler(CompileContext context);

    DensityFunction rewriteChildren(DfRewriteRule rule);

    Interval range();

    int domainAxes();

    MapCodec<? extends DensityFunction> codec();

    default DensityFunction clamp(float min, float max) { throw new UnsupportedOperationException(); }

    default DensityFunction reciprocal() { throw new UnsupportedOperationException(); }

    interface CompileContext {
        Noise createNoiseSampler(Holder<NormalNoise> noise);

        RandomSource createRandom(Identifier id);

        RandomSource createEndIslandRandom();
    }
}
