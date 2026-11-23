// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.texture;

public abstract class AbstractTexture implements AutoCloseable {
    protected com.mojang.blaze3d.textures.GpuSampler sampler;

    protected AbstractTexture() {}
    public com.mojang.blaze3d.textures.GpuTexture getTexture() { throw new UnsupportedOperationException(); }
    public com.mojang.blaze3d.textures.GpuSampler getSampler() { throw new UnsupportedOperationException(); }
    @Override public void close() { throw new UnsupportedOperationException(); }
}
