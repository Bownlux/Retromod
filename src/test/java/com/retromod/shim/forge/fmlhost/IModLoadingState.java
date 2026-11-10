/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.fmlhost;

/** Stands in for FML's loading state, which is looked up by its name. */
public interface IModLoadingState {
    String name();
}
