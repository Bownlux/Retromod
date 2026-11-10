// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.block;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;

public class BlockStateModelSet {
    private BlockStateModelSet() {}
    public BlockStateModel get(BlockState state) { throw new UnsupportedOperationException(); }
    public BlockStateModel missingModel() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.resources.model.sprite.Material.Baked getParticleMaterial(BlockState state) {
        throw new UnsupportedOperationException();
    }
}
