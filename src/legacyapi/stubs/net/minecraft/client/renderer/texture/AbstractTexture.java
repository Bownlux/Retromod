// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.texture;

public abstract class AbstractTexture implements AutoCloseable {
    protected AbstractTexture() {}
    @Override public void close() { throw new UnsupportedOperationException(); }
}
