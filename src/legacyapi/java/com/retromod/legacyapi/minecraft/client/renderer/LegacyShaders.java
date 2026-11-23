/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

/** The shader getters 1.21.2 removed from GameRenderer and RenderSystem, which have no current shader to answer with. */
public final class LegacyShaders {

    private LegacyShaders() {}

    /**
     * Every old shader getter, such as {@code GameRenderer.getPositionTexShader()}: null. The host picks a render
     * pipeline for each draw, old {@code setShader} calls are dropped, and old immediate draws are skipped.
     */
    public static Object none() {
        return null;
    }
}
