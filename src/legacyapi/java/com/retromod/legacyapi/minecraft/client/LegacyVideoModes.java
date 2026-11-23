/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.blaze3d.platform.VideoMode;

/** {@code VideoMode.getRefreshRate()}, a whole number of hertz before 26.3 made it fractional. */
public final class LegacyVideoModes {

    private LegacyVideoModes() {}

    /** The refresh rate rounded to whole hertz. */
    public static int getRefreshRate(VideoMode mode) {
        return Math.round(mode.getRefreshRate());
    }
}
