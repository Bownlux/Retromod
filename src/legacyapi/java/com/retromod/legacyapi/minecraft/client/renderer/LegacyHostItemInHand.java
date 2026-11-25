/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;

/** The renderer context's {@code getItemInHandRenderer()}, which 1.21.2 removed, on a host that still has the renderer. */
public final class LegacyHostItemInHand {

    private LegacyHostItemInHand() {}

    /** The entity render dispatcher's renderer, the one the context used to hand out. */
    public static ItemInHandRenderer get(Object context) {
        return Minecraft.getInstance().getEntityRenderDispatcher().getItemInHandRenderer();
    }
}
