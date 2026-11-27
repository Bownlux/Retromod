/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.registries;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.Optional;

/** Forge 1.20.1's {@code RegistryObject} members that NeoForge's {@code DeferredHolder} answers in another shape. */
public final class LegacyRegistryObjects {

    private LegacyRegistryObjects() {}

    /** {@code RegistryObject.getHolder()}: the holder is the object itself once its entry is registered. */
    public static Optional<Holder<?>> getHolder(DeferredHolder<?, ?> holder) {
        return holder.isBound() ? Optional.of(holder) : Optional.empty();
    }

    /** {@code RegistryObject.create(name, registryKey, modid)}: the mod id was only for lookups, so the holder needs the key and name. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static DeferredHolder<?, ?> create(Identifier name, ResourceKey<? extends Registry<?>> registryKey, String modId) {
        return DeferredHolder.create((ResourceKey) registryKey, name);
    }
}
