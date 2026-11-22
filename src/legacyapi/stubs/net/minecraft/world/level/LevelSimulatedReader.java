// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface LevelSimulatedReader {
    boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate);
}
