/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen.shared;

/** The vanilla {@code LayeredDraw.Layer} of 1.20.5 to 1.21.5, erased so every mod shares one type. */
@FunctionalInterface
public interface LegacyHudLayer {
    // Named for this type alone, so one class can implement it beside other shared types.
    void renderLegacyHudLayer(Object graphics, Object deltaTracker);
}
