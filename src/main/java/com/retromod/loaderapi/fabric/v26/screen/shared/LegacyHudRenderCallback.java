/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen.shared;

import com.retromod.loaderapi.fabric.v26.screen.LegacyHud;
import net.fabricmc.fabric.api.event.Event;

/** The old Fabric API {@code HudRenderCallback}, one type that every mod and library shares. */
public interface LegacyHudRenderCallback {

    /** The old event; Retromod draws it as one HUD element after the vanilla ones. */
    Event<LegacyHudRenderCallback> EVENT = LegacyHud.hudRenderEvent();

    /** The 1.21 shape, {@code onHudRender(GuiGraphics, DeltaTracker)}, with erased types. */
    default void renderLegacyHud(Object graphics, Object deltaTracker) {
        // A callback written for another shape gets its own method, found once per class.
        LegacyHud.dispatch(this, graphics, deltaTracker);
    }

    /** The 1.20 shape, {@code onHudRender(GuiGraphics, float)}, with erased types. */
    default void renderLegacyHudWithPartialTick(Object graphics, float tickDelta) {
        LegacyHud.dispatchPartialTick(this, graphics, tickDelta);
    }
}
