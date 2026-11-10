/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

/** Forge 1.20.1's arm pose transformer, which poses the model from the entity holding the item. */
public interface LegacyArmPoseTransformer {

    void applyTransform(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm);
}
