// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.vertex;

// The abstract members 26.2 and 26.3 share, and the whole-vertex default; 26.3 adds setUv3, which implementations declare on their own.
public interface VertexConsumer {
    VertexConsumer addVertex(float x, float y, float z);
    VertexConsumer setColor(int red, int green, int blue, int alpha);
    VertexConsumer setColor(int argb);
    VertexConsumer setUv(float u, float v);
    VertexConsumer setUv1(int u, int v);
    VertexConsumer setUv2(int u, int v);
    VertexConsumer setNormal(float x, float y, float z);
    VertexConsumer setLineWidth(float width);
    default void addVertex(float x, float y, float z, int argb, float u, float v, int overlay, int light, float normalX, float normalY,
            float normalZ) {
        throw new UnsupportedOperationException();
    }
    default void putBakedQuad(PoseStack.Pose pose, net.minecraft.client.resources.model.geometry.BakedQuad quad, QuadInstance instance) {
        throw new UnsupportedOperationException();
    }
}
