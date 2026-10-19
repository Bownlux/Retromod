package net.minecraft.client.color.block;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

public interface BlockTintSource {
    int color(BlockState state);
    default int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) { return color(state); }
    default int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        return colorInWorld(state, level, pos);
    }
    default Set<Object> relevantProperties() { return Set.of(); }
}
