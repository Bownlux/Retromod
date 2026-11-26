/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.entity;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.phys.Vec3;

/** Members of 1.21.1's PlayerRenderer that 1.21.9's AvatarRenderer dropped, for old mods that name the renamed class. */
public final class LegacyAvatarRenderers {

    // A crouching player's model sits two sixteenths of a block lower, scaled with the player.
    private static final double CROUCH_DROP = 2.0 / 16.0;

    private LegacyAvatarRenderers() {}

    /** The old first person arm draw into a buffer source: nothing, since 26.x draws arms only through its submit collector. */
    public static void renderRightHand(AvatarRenderer<?> renderer, Object poseStack, Object buffers, int light, AbstractClientPlayer player) {
        skippedArm(renderer);
    }

    /** The same for the left arm. */
    public static void renderLeftHand(AvatarRenderer<?> renderer, Object poseStack, Object buffers, int light, AbstractClientPlayer player) {
        skippedArm(renderer);
    }

    /** The old render offset of a player, which 26.x reads from the render state instead. */
    public static Vec3 getRenderOffset(AvatarRenderer<?> renderer, AbstractClientPlayer player, float partialTick) {
        if (player == null || !player.isCrouching()) return Vec3.ZERO;
        return new Vec3(0.0, -CROUCH_DROP * player.getScale(), 0.0);
    }

    private static void skippedArm(AvatarRenderer<?> renderer) {
        LegacyLog.warnOnce("avatar arm draw", "An old mod drew a first person arm through PlayerRenderer, which Minecraft 1.21.9 "
                + "replaced; Retromod skips that arm, since the new renderer draws arms only in its own frame pass");
    }
}
