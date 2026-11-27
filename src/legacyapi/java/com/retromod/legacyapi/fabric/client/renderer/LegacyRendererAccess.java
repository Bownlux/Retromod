/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

/** Fabric API's old {@code RendererAccess}, which hands out a renderer over the host's, Indigo or the one a renderer mod registered. */
public interface LegacyRendererAccess {
    LegacyRendererAccess INSTANCE = new LegacyRenderers.Access();

    void registerRenderer(LegacyRenderer plugin);

    LegacyRenderer getRenderer();

    boolean hasRenderer();
}
