// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface WorldGenLevel extends LevelAccessor {
    // Declared on LevelWriter and BlockGetter on the host.
    boolean setBlock(BlockPos pos, BlockState state, int flags);
    BlockState getBlockState(BlockPos pos);
    // A default method of LevelReader on the host.
    net.minecraft.world.level.chunk.ChunkAccess getChunk(BlockPos pos);
}
