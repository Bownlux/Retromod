// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.model.loading.v1;

public interface FabricModelManager {
    default <T> T getModel(ExtraModelKey<T> key) { throw new UnsupportedOperationException(); }
}
