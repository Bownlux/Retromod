/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import net.minecraft.client.Minecraft;

/** RenderSystem.recordRenderCall, which queued a task for the render thread, rebuilt on the game's task queue. */
public final class LegacyRenderCalls {

    private LegacyRenderCalls() {}

    /** {@code recordRenderCall(call)}: runs the call on the render thread, which is the client's main thread. */
    public static void recordRenderCall(LegacyRenderCall call) {
        Minecraft minecraft = Minecraft.getInstance();
        // Before the client exists there is no queue and nothing for the call to draw on yet.
        if (minecraft == null) {
            call.execute();
            return;
        }
        minecraft.execute(call::execute);
    }
}
