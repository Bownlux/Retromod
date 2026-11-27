/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

/** Client calls that mods for 1.19.4 and older made, each answered by the member that replaced it. */
public final class LegacyEraClientCalls {

    // 1.20 dropped the tutorial hint toast; an id of its own keeps the old toast apart from others, with the default time.
    private static final SystemToast.SystemToastId TUTORIAL_HINT = new SystemToast.SystemToastId();

    private LegacyEraClientCalls() {}

    /** {@code info.getSkinLocation()}: the skin texture, which 1.20.2 moved into the player's skin record. */
    public static Identifier getSkinLocation(PlayerInfo info) {
        return info.getSkin().body().texturePath();
    }

    /** {@code player.getSkinTextureLocation()}: the same. */
    public static Identifier getSkinTextureLocation(AbstractClientPlayer player) {
        return player.getSkin().body().texturePath();
    }

    /** {@code player.isRidingJumpable()}: whether the player rides something that jumps, which 1.19.3 hands out as the vehicle. */
    public static boolean isRidingJumpable(LocalPlayer player) {
        return player.jumpableVehicle() != null;
    }

    /** {@code SystemToastId.TUTORIAL_HINT}. */
    public static SystemToast.SystemToastId tutorialHint() {
        return TUTORIAL_HINT;
    }
}
