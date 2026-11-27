// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.block.dispatch;

import java.util.List;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.extensions.IForgeBlockStateModel;

// Forge patches the host interface to extend its block model extension.
public interface BlockStateModel extends IForgeBlockStateModel {
    void collectParts(RandomSource random, List<BlockStateModelPart> parts);
    Material.Baked particleMaterial();
    int materialFlags();
    default boolean hasMaterialFlag(int flag) { throw new UnsupportedOperationException(); }

    interface Unbaked extends net.minecraft.client.resources.model.ResolvableModel {
        BlockStateModel bake(net.minecraft.client.resources.model.ModelBaker baker);
    }

    interface UnbakedRoot extends net.minecraft.client.resources.model.ResolvableModel {
        BlockStateModel bake(net.minecraft.world.level.block.state.BlockState state, net.minecraft.client.resources.model.ModelBaker baker);
        Object visualEqualityGroup(net.minecraft.world.level.block.state.BlockState state);
    }
}
