/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Stands in for Minecraft's RegistryAccess and its frozen form, which a server hands out. */
public interface RegistryAccess extends HolderLookup.Provider {
    interface Frozen extends RegistryAccess {
    }
}
