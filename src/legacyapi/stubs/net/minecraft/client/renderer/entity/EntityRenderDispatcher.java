// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.entity;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

public class EntityRenderDispatcher {
    public <S extends EntityRenderState> EntityRenderer<?, ? super S> getRenderer(S state) { throw new UnsupportedOperationException(); }
    public <E extends net.minecraft.world.entity.Entity> EntityRenderState extractEntity(E entity, float partialTick) {
        throw new UnsupportedOperationException();
    }
    public <S extends EntityRenderState> void submit(S renderState, net.minecraft.client.renderer.state.level.CameraRenderState camera,
            double x, double y, double z, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector) {
        throw new UnsupportedOperationException();
    }
}
