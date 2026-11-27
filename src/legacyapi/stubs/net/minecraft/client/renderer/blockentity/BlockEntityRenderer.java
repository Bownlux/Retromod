// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.blockentity;

public interface BlockEntityRenderer<T, S> {
    default boolean shouldRenderOffScreen() { throw new UnsupportedOperationException(); }
}
