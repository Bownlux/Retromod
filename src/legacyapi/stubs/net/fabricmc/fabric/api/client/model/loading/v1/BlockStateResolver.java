// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.model.loading.v1;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface BlockStateResolver {
    void resolveBlockStates(Context context);

    interface Context {
        Block block();
        void setModel(BlockState state, BlockStateModel.UnbakedRoot model);
    }
}
