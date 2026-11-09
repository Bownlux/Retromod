/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.IArmPoseTransformer;

/** Forge 1.20.1's {@code ArmPose.create(name, twoHanded, transformer)} on Forge 26.x, which poses arms from render states. */
public final class LegacyArmPoses {

    private LegacyArmPoses() {}

    /** Adds the pose through the host's own enum extension, with the old transformer behind an adapter. */
    public static HumanoidModel.ArmPose create(String name, boolean twoHanded, LegacyArmPoseTransformer transformer) {
        try {
            // A two-handed pose sets both arms, so the host leaves the other arm alone, as it does for a bow.
            return HumanoidModel.ArmPose.create(name, twoHanded, twoHanded, new Adapter(name, transformer));
        } catch (IllegalStateException notExtended) {
            // Forge's create throws this when its enum extender did not run on the class.
            LegacyLog.warnOnce("armpose:" + name, "The arm pose " + name + " could not be added on this Forge version,"
                    + " so items using it hold the plain item pose (ITEM): " + notExtended.getMessage());
            return HumanoidModel.ArmPose.ITEM;
        }
    }

    /** Hands the host's render-state call to the old transformer with the entity it expects. */
    static final class Adapter implements IArmPoseTransformer {

        private final String name;
        private final LegacyArmPoseTransformer transformer;
        private volatile boolean stopped;

        Adapter(String name, LegacyArmPoseTransformer transformer) {
            this.name = name;
            this.transformer = transformer;
        }

        @Override
        public void applyTransform(HumanoidModel<? extends HumanoidRenderState> model, HumanoidRenderState state,
                HumanoidArm arm) {
            if (stopped || transformer == null) return;
            // Only the one entity the state was made from; a figure drawn outside the world, or two entities the
            // state fits equally, keep the arm as the model set it, so no entity's data poses another's arm.
            LivingEntity holder = RenderStateEntities.livingEntity(state);
            if (holder == null) return;
            try {
                transformer.applyTransform(model, holder, arm);
            } catch (RuntimeException | LinkageError failure) {
                // A failure here would reach the renderer every frame, so this pose stops posing after one warning.
                stopped = true;
                LegacyLog.warnOnce("armpose-stopped:" + name, "The arm pose " + name + " stopped posing arms, because"
                        + " its transformer failed: " + failure);
            }
        }
    }
}
