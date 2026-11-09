/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Forge 26.2's item extension: the stack's capability provider comes from getCapabilityProvider, none by default. */
public interface IForgeItem {
    default ICapabilityProvider getCapabilityProvider(ItemStack stack) {
        return null;
    }
}
