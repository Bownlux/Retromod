// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.feature.foliageplacers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public abstract class FoliagePlacer {
    public interface FoliageSetter {
        void set(BlockPos pos, BlockState state);
        boolean isSet(BlockPos pos);
    }
}
