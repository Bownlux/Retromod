// Compile-only stub of a Fabric API 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.render.fluid.v1;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public interface FluidRenderHandler {
    default void renderFluid(FluidRenderer fluidRenderer, BlockPos pos, BlockAndTintGetter level, FluidRenderer.Output output,
            BlockState blockState, FluidState fluidState) {
        throw new UnsupportedOperationException();
    }
}
