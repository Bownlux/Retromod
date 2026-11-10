/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

/** Stands in for FML's loading context: the container of the mod running now, Minecraft's outside any mod. */
public class ModLoadingContext {
    public static ModContainer active;
    private static final ModLoadingContext CONTEXT = new ModLoadingContext();

    public static ModLoadingContext get() {
        return CONTEXT;
    }

    public ModContainer getContainer() {
        return active;
    }
}
