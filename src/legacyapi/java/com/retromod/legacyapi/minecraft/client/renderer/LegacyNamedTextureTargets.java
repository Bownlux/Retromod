/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;

/** {@code new TextureTarget(width, height, useDepth, clearError)} on 1.21.11 to 26.1, which name the target. */
public final class LegacyNamedTextureTargets {

    static final String NAME = "retromod_legacy_target";

    private LegacyNamedTextureTargets() {}

    /** The same target under a fixed name; the error flag is gone. */
    public static TextureTarget create(int width, int height, boolean useDepth, boolean clearError) {
        return new TextureTarget(NAME, width, height, useDepth);
    }
}
