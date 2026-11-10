/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** A host object behind Forge's interface that records the registries each save and load passes it. */
public class RecordingSerializable implements INBTSerializable<Tag> {
    public HolderLookup.Provider savedWith;
    public HolderLookup.Provider loadedWith;
    public boolean fail;

    public RecordingSerializable() {}

    @Override
    public Tag serializeNBT(HolderLookup.Provider registries) {
        savedWith = registries;
        if (fail) throw new IllegalStateException("a save that fails");
        return new CompoundTag();
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, Tag nbt) {
        loadedWith = registries;
    }
}
