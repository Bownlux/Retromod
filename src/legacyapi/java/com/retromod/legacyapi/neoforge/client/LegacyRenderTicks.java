/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client;

import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Forge's {@code TickEvent.RenderTickEvent.renderTickTime} on NeoForge's frame event, which keeps the partial tick in a timer. */
public final class LegacyRenderTicks {

    private LegacyRenderTicks() {}

    /** The partial tick of the frame, as the old field held it; a paused game holds it still, as before. */
    public static float renderTickTime(Object event) {
        return ((RenderFrameEvent) event).getPartialTick().getGameTimeDeltaPartialTick(false);
    }
}
