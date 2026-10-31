/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content.shared;

import com.retromod.loaderapi.fabric.v26.content.LegacyContentRegistries;

/** The old Fabric API {@code FuelRegistry} that 1.21.2 replaced, one type for every mod. */
public interface LegacyFuelRegistry {

    /** The old {@code INSTANCE}. */
    LegacyFuelRegistry INSTANCE = new LegacyFuelRegistry() { };

    // Default methods, so a mod class that implemented the old interface still loads. The tag
    // forms have their own names, because both forms erase to the same descriptor.

    /** Old {@code get(ItemLike)}: the burn time an old mod set, or null. */
    default Object get(Object item) {
        return LegacyContentRegistries.fuelTime(item);
    }

    /** Old {@code add(ItemLike, Integer)}. */
    default void add(Object item, Object ticks) {
        LegacyContentRegistries.addFuel(item, ticks);
    }

    /** Old {@code add(TagKey, Integer)}. */
    default void addTag(Object tag, Object ticks) {
        LegacyContentRegistries.addFuelTag(tag, ticks);
    }

    /** Old {@code remove(ItemLike)}, which also stood for {@code clear(ItemLike)}. */
    default void remove(Object item) {
        LegacyContentRegistries.removeFuel(item);
    }

    /** Old {@code remove(TagKey)}, which also stood for {@code clear(TagKey)}. */
    default void removeTag(Object tag) {
        LegacyContentRegistries.removeFuelTag(tag);
    }
}
