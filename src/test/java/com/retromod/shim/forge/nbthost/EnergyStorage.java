/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Forge 26.2's EnergyStorage save shape; it records the registries it was given. */
public class EnergyStorage implements INBTSerializable<Tag> {
    public HolderLookup.Provider lastRegistries;
    public Tag stored = new CompoundTag();

    public EnergyStorage() {}

    @Override
    public Tag serializeNBT(HolderLookup.Provider registries) {
        lastRegistries = registries;
        return stored;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, Tag nbt) {
        lastRegistries = registries;
        stored = nbt;
    }
}
