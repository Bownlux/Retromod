/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.guihost;

/** Stands in for the frame's delta tracker. */
public interface DeltaTracker {
    float getGameTimeDeltaPartialTick(boolean runsNormally);
}
