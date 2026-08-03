/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.entity.EntityPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Load-time regression for the expanded Pose enum constructor. */
@Mixin(EntityPose.class)
public abstract class LegacyPoseInvokerMixin {

    @Invoker("<init>")
    public static EntityPose retromod$invokePoseConstructor(String name, int ordinal) {
        throw new AssertionError("Mixin did not replace the legacy Pose constructor invoker");
    }
}
