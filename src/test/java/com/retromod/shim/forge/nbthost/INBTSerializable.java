/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Forge 26.2's INBTSerializable shape: the save and the load take the registries. */
public interface INBTSerializable<T extends Tag> {
    T serializeNBT(HolderLookup.Provider registries);

    void deserializeNBT(HolderLookup.Provider registries, T nbt);
}
