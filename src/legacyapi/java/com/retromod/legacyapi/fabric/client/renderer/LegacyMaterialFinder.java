/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Fabric API's old {@code MaterialFinder}, including the sprite-index forms 1.20 and 1.21 still accepted. */
public interface LegacyMaterialFinder extends LegacyMaterialView {
    LegacyMaterialFinder blendMode(LegacyBlendMode blendMode);

    LegacyMaterialFinder disableColorIndex(boolean disable);

    LegacyMaterialFinder emissive(boolean isEmissive);

    LegacyMaterialFinder disableDiffuse(boolean disable);

    LegacyMaterialFinder ambientOcclusion(TriState mode);

    LegacyMaterialFinder glint(TriState mode);

    LegacyMaterialFinder shadeMode(LegacyShadeMode mode);

    LegacyMaterialFinder copyFrom(LegacyMaterialView material);

    LegacyMaterialFinder clear();

    LegacyRenderMaterial find();

    default LegacyMaterialFinder blendMode(int spriteIndex, RenderType renderLayer) {
        return blendMode(LegacyBlendMode.fromRenderLayer(renderLayer));
    }

    default LegacyMaterialFinder blendMode(int spriteIndex, LegacyBlendMode blendMode) {
        return blendMode(blendMode == null ? LegacyBlendMode.DEFAULT : blendMode);
    }

    default LegacyMaterialFinder disableColorIndex(int spriteIndex, boolean disable) {
        return disableColorIndex(disable);
    }

    default LegacyMaterialFinder emissive(int spriteIndex, boolean isEmissive) {
        return emissive(isEmissive);
    }

    default LegacyMaterialFinder disableDiffuse(int spriteIndex, boolean disable) {
        return disableDiffuse(disable);
    }

    default LegacyMaterialFinder disableAo(int spriteIndex, boolean disable) {
        return ambientOcclusion(disable ? TriState.FALSE : TriState.DEFAULT);
    }

    default LegacyMaterialFinder spriteDepth(int depth) {
        return this;
    }
}
