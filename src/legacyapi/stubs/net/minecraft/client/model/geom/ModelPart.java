// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.model.geom;

public final class ModelPart {
    public boolean visible;
    public float x, y, z, xRot, yRot, zRot, xScale, yScale, zScale;
    public ModelPart(java.util.List<Cube> cubes, java.util.Map<String, ModelPart> children) { throw new UnsupportedOperationException(); }
    public void render(com.mojang.blaze3d.vertex.PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay, int color) { throw new UnsupportedOperationException(); }
    public boolean hasChild(String name) { throw new UnsupportedOperationException(); }
    public ModelPart getChild(String name) { throw new UnsupportedOperationException(); }
    public static final class Cube {
        public void compile(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay, int color) { throw new UnsupportedOperationException(); }
    }
    public PartPose storePose() { throw new UnsupportedOperationException(); }
    public void loadPose(PartPose pose) { throw new UnsupportedOperationException(); }
    public java.util.List<ModelPart> getAllParts() { throw new UnsupportedOperationException(); }
}
