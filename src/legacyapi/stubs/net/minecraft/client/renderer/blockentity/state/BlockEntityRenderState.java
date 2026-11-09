// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.blockentity.state;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

public class BlockEntityRenderState {
    public int lightCoords;

    public BlockEntityRenderState() {}

    public static void extractBase(BlockEntity blockEntity, BlockEntityRenderState state, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        throw new UnsupportedOperationException();
    }
}
