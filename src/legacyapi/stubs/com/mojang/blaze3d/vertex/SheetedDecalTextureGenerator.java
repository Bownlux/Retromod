// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.vertex;

public class SheetedDecalTextureGenerator implements VertexConsumer {
    public SheetedDecalTextureGenerator(VertexConsumer delegate, PoseStack.Pose pose, float textureScale) {}
    @Override public VertexConsumer addVertex(float x, float y, float z) { throw new UnsupportedOperationException(); }
    @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { throw new UnsupportedOperationException(); }
    @Override public VertexConsumer setColor(int argb) { throw new UnsupportedOperationException(); }
    @Override public VertexConsumer setUv(float u, float v) { throw new UnsupportedOperationException(); }
    @Override public VertexConsumer setUv1(int u, int v) { throw new UnsupportedOperationException(); }
    @Override public VertexConsumer setUv2(int u, int v) { throw new UnsupportedOperationException(); }
    @Override public VertexConsumer setNormal(float x, float y, float z) { throw new UnsupportedOperationException(); }
    @Override public VertexConsumer setLineWidth(float width) { throw new UnsupportedOperationException(); }
}
