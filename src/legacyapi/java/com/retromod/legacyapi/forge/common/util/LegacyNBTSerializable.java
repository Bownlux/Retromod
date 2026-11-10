/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common.util;

import com.retromod.legacyapi.minecraft.core.LegacyRegistryContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

/** Forge 1.20.1's {@code INBTSerializable}, whose methods took no registries, on top of Forge 26.2's, which passes them. */
public interface LegacyNBTSerializable<T extends Tag> extends INBTSerializable<T> {

    /** The old save an old mod implements. */
    T serializeNBT();

    /** The old load an old mod implements. */
    void deserializeNBT(T nbt);

    // While the old method runs, the registries Forge passed are the current ones, for the old calls inside it.
    @Override
    default T serializeNBT(HolderLookup.Provider registries) {
        Object previous = LegacyRegistryContext.enter(registries);
        try {
            return serializeNBT();
        } finally {
            LegacyRegistryContext.exit(previous);
        }
    }

    @Override
    default void deserializeNBT(HolderLookup.Provider registries, T nbt) {
        Object previous = LegacyRegistryContext.enter(registries);
        try {
            deserializeNBT(nbt);
        } finally {
            LegacyRegistryContext.exit(previous);
        }
    }
}
