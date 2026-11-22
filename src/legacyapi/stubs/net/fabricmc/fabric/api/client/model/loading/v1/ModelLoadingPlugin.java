// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.model.loading.v1;

import net.fabricmc.fabric.api.event.Event;

@FunctionalInterface
public interface ModelLoadingPlugin {
    static void register(ModelLoadingPlugin plugin) { throw new UnsupportedOperationException(); }

    void initialize(Context pluginContext);

    interface Context {
        Event<ModelModifier.AfterBakeItem> modifyItemModelAfterBake();
    }
}
