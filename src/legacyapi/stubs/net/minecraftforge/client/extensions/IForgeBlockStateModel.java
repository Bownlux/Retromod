// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.extensions;

import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

public interface IForgeBlockStateModel {
    default ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) { throw new UnsupportedOperationException(); }
    default Material.Baked particleMaterial(ModelData data) { throw new UnsupportedOperationException(); }
    default void collectParts(RandomSource random, List<BlockStateModelPart> parts, ModelData data) { throw new UnsupportedOperationException(); }
}
