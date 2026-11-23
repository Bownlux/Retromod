// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.model;

import java.util.function.Function;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

public abstract class Model<S> {
    protected final ModelPart root = null;
    public Model(ModelPart root, Function<Identifier, RenderType> renderType) { throw new UnsupportedOperationException(); }
    public final void renderToBuffer(com.mojang.blaze3d.vertex.PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay, int color) { throw new UnsupportedOperationException(); }
    public final ModelPart root() { throw new UnsupportedOperationException(); }
    public void setupAnim(S state) { throw new UnsupportedOperationException(); }
}
