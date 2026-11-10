/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.eb7;

/** Stands in for Forge 26.2's static ModLoader: a test says when mod loading has completed. */
public final class ModLoader {

    /** Whether FML has finished loading mods, which opens Forge 1.20.1's game bus. */
    public static volatile boolean complete;

    private ModLoader() {}

    public static boolean hasCompletedState(IModLoadingState state) {
        return complete;
    }
}
