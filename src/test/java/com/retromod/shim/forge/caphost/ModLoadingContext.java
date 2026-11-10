/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

/** Stands in for FML's ModLoadingContext: the container of the mod constructing now. */
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
