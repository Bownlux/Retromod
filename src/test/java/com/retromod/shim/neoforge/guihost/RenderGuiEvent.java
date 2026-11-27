/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.guihost;

/** NeoForge 1.21's HUD event: a tracker and no window. Its tracker answers 1.0 for false and 0.25 for true. */
public class RenderGuiEvent {
    public DeltaTracker getPartialTick() {
        return runsNormally -> runsNormally ? 0.25F : 1.0F;
    }
}
