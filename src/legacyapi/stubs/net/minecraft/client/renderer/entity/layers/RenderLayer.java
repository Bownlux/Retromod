// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.entity.layers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

public abstract class RenderLayer<S extends EntityRenderState, M extends EntityModel<? super S>> {
    public RenderLayer(RenderLayerParent<S, M> renderer) {}
    public M getParentModel() { throw new UnsupportedOperationException(); }
    public abstract void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, S state, float yRot, float xRot);
}
