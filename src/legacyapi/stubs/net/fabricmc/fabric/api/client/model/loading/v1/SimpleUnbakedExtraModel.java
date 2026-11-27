// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.model.loading.v1;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.Identifier;

public final class SimpleUnbakedExtraModel<T> implements UnbakedExtraModel<T> {
    private SimpleUnbakedExtraModel() {}
    public static SimpleUnbakedExtraModel<BlockStateModel> blockStateModel(Identifier model) { throw new UnsupportedOperationException(); }
    public void resolveDependencies(ResolvableModel.Resolver resolver) { throw new UnsupportedOperationException(); }
    public T bake(ModelBaker baker) { throw new UnsupportedOperationException(); }
}
