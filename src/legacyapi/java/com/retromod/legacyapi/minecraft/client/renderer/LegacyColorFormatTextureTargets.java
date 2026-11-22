/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;

/** {@code new TextureTarget(width, height, useDepth, clearError)} on 26.2, which names the target and its color format. */
public final class LegacyColorFormatTextureTargets {

    // Spelled out here, so this class carries no reference to the older helper, whose constructor this host lacks.
    static final String NAME = "retromod_legacy_target";

    private LegacyColorFormatTextureTargets() {}

    /** An 8-bit color target, as the old constructor made; the error flag is gone. */
    public static TextureTarget create(int width, int height, boolean useDepth, boolean clearError) {
        return new TextureTarget(NAME, width, height, useDepth, GpuFormat.RGBA8_UNORM);
    }
}
