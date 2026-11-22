/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;

/** {@code new TextureTarget(width, height, useDepth, clearError)} on 26.3, which names the target and its formats. */
public final class LegacyTextureTargets {

    // Spelled out here, so this class carries no reference to the older helper, whose constructor this host lacks.
    static final String NAME = "retromod_legacy_target";

    private LegacyTextureTargets() {}

    /** An 8-bit color target, with a 32-bit depth buffer when the old call asked for depth; the error flag is gone. */
    public static TextureTarget create(int width, int height, boolean useDepth, boolean clearError) {
        return new TextureTarget(NAME, width, height, GpuFormat.RGBA8_UNORM,
                useDepth ? GpuFormat.D32_FLOAT : null);
    }
}
