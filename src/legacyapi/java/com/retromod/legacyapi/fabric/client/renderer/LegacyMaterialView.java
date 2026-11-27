/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import net.fabricmc.fabric.api.util.TriState;

/** Fabric API's old {@code MaterialView}: what a render material asks of a quad. */
public interface LegacyMaterialView {
    LegacyBlendMode blendMode();

    boolean disableColorIndex();

    boolean emissive();

    boolean disableDiffuse();

    TriState ambientOcclusion();

    TriState glint();

    default LegacyShadeMode shadeMode() {
        return LegacyShadeMode.ENHANCED;
    }
}
