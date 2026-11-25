/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/** Window members that moved off the window: the frame rate limit in 1.21.2 and the vsync switch in 26.2. */
public final class LegacyWindowCalls {

    private LegacyWindowCalls() {}

    /** {@code window.getFramerateLimit()}: the limit the game's frame rate tracker applies now. */
    public static int getFramerateLimit(Window window) {
        return Minecraft.getInstance().getFramerateLimitTracker().getFramerateLimit();
    }

    /** {@code window.setFramerateLimit(limit)}: sets the same tracker's limit. */
    public static void setFramerateLimit(Window window, int limit) {
        Minecraft.getInstance().getFramerateLimitTracker().setFramerateLimit(limit);
    }

    /** {@code window.updateVsync(enabled)}: 26.2 applies vsync from the saved option only, so this changes nothing. */
    public static void updateVsync(Window window, boolean enabled) {
        // Setting the option instead would change the player's saved setting, which the old call never did.
    }
}
