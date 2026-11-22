// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged. On 26.3 it moves to LegacyFeatureBridge's generated context.
package net.minecraft.world.level.levelgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

public class FeaturePlaceContext {
    public WorldGenLevel level() { throw new UnsupportedOperationException(); }
    public RandomSource random() { throw new UnsupportedOperationException(); }
    public BlockPos origin() { throw new UnsupportedOperationException(); }
    public FeatureConfiguration config() { throw new UnsupportedOperationException(); }
}
