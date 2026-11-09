/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.util;

/** FastColor.ABGR32, the packed alpha, blue, green, red color helpers Minecraft removed with FastColor. */
public final class LegacyAbgr32 {

    private LegacyAbgr32() {}

    public static int alpha(int color) {
        return color >>> 24;
    }

    public static int red(int color) {
        return color & 0xFF;
    }

    public static int green(int color) {
        return color >> 8 & 0xFF;
    }

    public static int blue(int color) {
        return color >> 16 & 0xFF;
    }

    public static int transparent(int color) {
        return color & 0x00FFFFFF;
    }

    public static int opaque(int color) {
        return color | 0xFF000000;
    }

    /** {@code color(alpha, blue, green, red)}, each channel 0 to 255. */
    public static int color(int alpha, int blue, int green, int red) {
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    /** {@code color(alpha, bgr)}: a new alpha over the low 24 bits. */
    public static int color(int alpha, int bgr) {
        return alpha << 24 | bgr & 0x00FFFFFF;
    }

    /** {@code fromArgb32(argb)}: the same color with red and blue swapped into this order. */
    public static int fromArgb32(int argb) {
        return argb & 0xFF00FF00 | (argb & 0x00FF0000) >> 16 | (argb & 0x000000FF) << 16;
    }
}
