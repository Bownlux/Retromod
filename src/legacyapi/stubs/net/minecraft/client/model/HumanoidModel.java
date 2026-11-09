// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.model;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

public class HumanoidModel<T extends HumanoidRenderState> extends EntityModel<T> {
    // Final on the host.
    public net.minecraft.client.model.geom.ModelPart head;
    public net.minecraft.client.model.geom.ModelPart hat;
    public net.minecraft.client.model.geom.ModelPart body;
    public net.minecraft.client.model.geom.ModelPart rightArm;
    public net.minecraft.client.model.geom.ModelPart leftArm;
    public net.minecraft.client.model.geom.ModelPart rightLeg;
    public net.minecraft.client.model.geom.ModelPart leftLeg;

    // A class extending Enum on the host; Forge patches in create and the arm pose transformer.
    public enum ArmPose {
        ITEM;

        public static ArmPose create(String name, boolean twoHanded, boolean affectsOffhandPose,
                net.minecraftforge.client.IArmPoseTransformer transformer) {
            throw new UnsupportedOperationException();
        }
    }
}
