// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.model.loading.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.world.level.block.Block;

@FunctionalInterface
public interface ModelLoadingPlugin {
    static void register(ModelLoadingPlugin plugin) { throw new UnsupportedOperationException(); }

    void initialize(Context pluginContext);

    interface Context {
        void registerBlockStateResolver(Block block, BlockStateResolver resolver);
        <T> void addModel(ExtraModelKey<T> key, UnbakedExtraModel<T> model);
        Event<ModelModifier.OnLoad> modifyModelOnLoad();
        Event<ModelModifier.OnLoadBlock> modifyBlockModelOnLoad();
        Event<ModelModifier.BeforeBakeBlock> modifyBlockModelBeforeBake();
        Event<ModelModifier.AfterBakeBlock> modifyBlockModelAfterBake();
        Event<ModelModifier.BeforeBakeItem> modifyItemModelBeforeBake();
        Event<ModelModifier.AfterBakeItem> modifyItemModelAfterBake();
    }
}
