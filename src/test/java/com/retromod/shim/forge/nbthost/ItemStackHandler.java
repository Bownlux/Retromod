/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Forge 26.2's ItemStackHandler save shape; it records the registries it was given. */
public class ItemStackHandler implements INBTSerializable<CompoundTag> {
    public HolderLookup.Provider lastRegistries;
    public Tag items = new CompoundTag();

    public ItemStackHandler() {}

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        lastRegistries = registries;
        CompoundTag saved = new CompoundTag();
        saved.put("Items", items);
        return saved;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag saved) {
        lastRegistries = registries;
        items = saved.get("Items");
    }
}
