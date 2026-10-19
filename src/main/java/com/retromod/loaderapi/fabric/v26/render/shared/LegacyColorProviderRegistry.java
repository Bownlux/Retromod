/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render.shared;

import com.retromod.loaderapi.fabric.v26.render.LegacyColorProviders;

/** The old Fabric API {@code ColorProviderRegistry}, one type for every mod that names it. */
public interface LegacyColorProviderRegistry {

    /** Item colors: an old {@code ItemColor} per item. */
    LegacyColorProviderRegistry ITEM = LegacyColorProviders.ITEM;

    /** Block colors: an old {@code BlockColor} per block. */
    LegacyColorProviderRegistry BLOCK = LegacyColorProviders.BLOCK;

    /** Old {@code register(Provider, T...)}; the old descriptor was already erased. */
    void register(Object provider, Object... objects);

    /** Old {@code get(T)}: the provider for {@code object}, or null. */
    Object get(Object object);
}
