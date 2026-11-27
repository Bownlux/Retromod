// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.platform.Transparency;

public class TextureAtlasSprite {
    public float getU(float u) { throw new UnsupportedOperationException(); }
    public float getV(float v) { throw new UnsupportedOperationException(); }
    private TextureAtlasSprite() {}
    public float getU0() { throw new UnsupportedOperationException(); }
    public float getU1() { throw new UnsupportedOperationException(); }
    public float getV0() { throw new UnsupportedOperationException(); }
    public float getV1() { throw new UnsupportedOperationException(); }
    public Transparency transparency() { throw new UnsupportedOperationException(); }
    public SpriteContents contents() { throw new UnsupportedOperationException(); }
    // Declared here on 26.2 and inherited from UvMapping on 26.3; a virtual call resolves either way.
    public com.mojang.blaze3d.vertex.VertexConsumer wrap(com.mojang.blaze3d.vertex.VertexConsumer consumer) { throw new UnsupportedOperationException(); }
}
