/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

/** Stands in for {@code RenderSystem}. A test never runs on the render thread, so no atlas is built. */
public final class RenderSystem {
    private RenderSystem() {}

    public static boolean isOnRenderThread() {
        return false;
    }
}
