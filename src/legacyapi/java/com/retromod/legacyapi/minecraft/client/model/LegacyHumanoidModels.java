/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/** HumanoidModel.setAllVisible and copyPropertiesTo, which Minecraft removed once its models took their pose from a render state. */
public final class LegacyHumanoidModels {

    private LegacyHumanoidModels() {}

    /** {@code setAllVisible(visible)}: shows or hides the head, hat, body, arms and legs. */
    public static void setAllVisible(Object model, boolean visible) {
        for (ModelPart part : parts((HumanoidModel<?>) model)) part.visible = visible;
    }

    /** {@code copyPropertiesTo(other)}: gives the other model this one's part poses. The old animation flags are gone. */
    public static void copyPropertiesTo(Object model, Object other) {
        ModelPart[] from = parts((HumanoidModel<?>) model);
        ModelPart[] to = parts((HumanoidModel<?>) other);
        for (int i = 0; i < from.length; i++) to[i].loadPose(from[i].storePose());
    }

    private static ModelPart[] parts(HumanoidModel<?> model) {
        return new ModelPart[] {model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg,
                model.leftLeg};
    }
}
