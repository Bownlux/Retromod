/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.renderpearl.backend.opengl.GlStateManager;

/** The blend switches of the GL state manager on 26.3, which now name the draw buffer they apply to. */
public final class LegacyBlendCalls {

    private LegacyBlendCalls() {}

    /** {@code GlStateManager._enableBlend()}: blending on the first draw buffer, the only one old code drew to. */
    public static void enableBlend() {
        GlStateManager._enableBlend(0);
    }

    /** {@code GlStateManager._disableBlend()}. */
    public static void disableBlend() {
        GlStateManager._disableBlend(0);
    }
}
