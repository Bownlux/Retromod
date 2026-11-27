/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import net.minecraft.resources.Identifier;

/** Fabric API's old {@code RenderMaterial}; 26.x sets these properties on each quad, which Retromod does when a mod applies one. */
public interface LegacyRenderMaterial extends LegacyMaterialView {
    Identifier MATERIAL_STANDARD = Identifier.fromNamespaceAndPath("fabric", "standard");

    default int spriteDepth() {
        return 1;
    }
}
