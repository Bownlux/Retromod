// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.block.model;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.world.level.block.state.BlockState;

// Minecraft 26.1 gave the old JSON model's name to this block render model.
public interface BlockModel {
    void update(BlockModelRenderState renderState, BlockState state, BlockDisplayContext context, long seed);
}
