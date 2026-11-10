/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.model.HumanoidModel;

/** Forge 1.20.1's {@code ArmPose.create} on NeoForge, which adds arm poses only from metadata it reads before the game starts. */
public final class LegacyNeoArmPoses {

    private LegacyNeoArmPoses() {}

    /** The plain item pose, which NeoForge also uses when an item names no pose. */
    public static HumanoidModel.ArmPose create(String name, boolean twoHanded, LegacyArmPoseTransformer transformer) {
        LegacyLog.warnOnce("armpose:" + name, "The arm pose " + name + " cannot be added on NeoForge while the game"
                + " runs, so items using it hold the plain item pose (ITEM)");
        return HumanoidModel.ArmPose.ITEM;
    }
}
