/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core.component;

import java.util.Optional;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;

/** DataComponentPatch.get(type), which 26.1 replaced with a lookup over a prototype. */
public final class LegacyComponentPatches {

    private LegacyComponentPatches() {}

    /** Null when the patch leaves the component alone, empty when it removes it, else the value it sets. */
    public static Optional<?> get(Object patch, DataComponentType<?> type) {
        DataComponentPatch.SplitResult split = ((DataComponentPatch) patch).split();
        if (split.removed().contains(type)) return Optional.empty();
        Object value = split.added().get(type);
        return value == null ? null : Optional.of(value);
    }
}
