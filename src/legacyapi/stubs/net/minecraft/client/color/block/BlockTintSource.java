// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.client.color.block;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface BlockTintSource {
    int color(BlockState state);
    default int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) { throw new UnsupportedOperationException(); }
}
