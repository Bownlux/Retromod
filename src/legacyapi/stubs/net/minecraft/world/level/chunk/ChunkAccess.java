// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public abstract class ChunkAccess {
    public abstract BlockState setBlockState(BlockPos pos, BlockState state, int flags);
}
