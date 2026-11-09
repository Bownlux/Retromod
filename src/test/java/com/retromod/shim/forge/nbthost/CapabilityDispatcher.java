/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

/** Saves and loads attached providers as Forge 26.2's dispatcher does: through the registry-taking methods, by key. */
public final class CapabilityDispatcher implements INBTSerializable<CompoundTag> {
    private final String[] names;
    private final INBTSerializable<Tag>[] writers;

    public CapabilityDispatcher(String[] names, INBTSerializable<Tag>[] writers) {
        this.names = names;
        this.writers = writers;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        CompoundTag saved = new CompoundTag();
        for (int i = 0; i < writers.length; i++) saved.put(names[i], writers[i].serializeNBT(registries));
        return saved;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag saved) {
        for (int i = 0; i < writers.length; i++) {
            if (saved.contains(names[i])) writers[i].deserializeNBT(registries, saved.get(names[i]));
        }
    }
}
