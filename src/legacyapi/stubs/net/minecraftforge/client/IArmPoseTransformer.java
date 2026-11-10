// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;

public interface IArmPoseTransformer {
    void applyTransform(HumanoidModel<? extends HumanoidRenderState> model, HumanoidRenderState state, HumanoidArm arm);
}
