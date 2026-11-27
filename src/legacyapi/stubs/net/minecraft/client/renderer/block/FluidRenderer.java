// Compile-only stub of a Minecraft 26.x host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.block;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public class FluidRenderer {
    private FluidRenderer() {}
    public void tesselate(BlockAndTintGetter level, BlockPos pos, Output output, BlockState blockState, FluidState fluidState) {
        throw new UnsupportedOperationException();
    }

    @FunctionalInterface
    public interface Output {
        VertexConsumer getBuilder(ChunkSectionLayer layer);
    }
}
