// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.densityfunction.op;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;

public final class CacheFunction implements DensityFunction {
    public DensityFunction input() { throw new UnsupportedOperationException(); }
    public DensitySampler compileSampler(CompileContext context) { throw new UnsupportedOperationException(); }
    public DensityFunction rewriteChildren(DfRewriteRule rule) { throw new UnsupportedOperationException(); }
    public Interval range() { throw new UnsupportedOperationException(); }
    public int domainAxes() { throw new UnsupportedOperationException(); }
    public MapCodec<CacheFunction> codec() { throw new UnsupportedOperationException(); }
}
