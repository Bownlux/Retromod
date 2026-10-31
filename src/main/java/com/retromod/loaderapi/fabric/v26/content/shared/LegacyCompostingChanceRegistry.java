/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content.shared;

import com.retromod.loaderapi.fabric.v26.content.LegacyContentRegistries;

/** The old Fabric API {@code CompostingChanceRegistry}, and 26.1's {@code CompostableRegistry}, for every mod. */
public interface LegacyCompostingChanceRegistry {

    /** The old {@code INSTANCE}. */
    LegacyCompostingChanceRegistry INSTANCE = new LegacyCompostingChanceRegistry() { };

    // Default methods, so a mod class that implemented the old interface still loads. The tag
    // forms have their own names, because both forms erase to the same descriptor.

    /** Old {@code get(ItemLike)}: the chance an old mod set, or null. */
    default Object get(Object item) {
        return LegacyContentRegistries.compostChance(item);
    }

    /** Old {@code add(ItemLike, Float)}. */
    default void add(Object item, Object chance) {
        LegacyContentRegistries.addCompostable(item, chance);
    }

    /** Old {@code add(TagKey, Float)}. */
    default void addTag(Object tag, Object chance) {
        LegacyContentRegistries.addCompostableTag(tag, chance);
    }

    /** Old {@code remove(ItemLike)}, which also stood for {@code clear(ItemLike)}. */
    default void remove(Object item) {
        LegacyContentRegistries.removeCompostable(item);
    }

    /** Old {@code remove(TagKey)}, which also stood for {@code clear(TagKey)}. */
    default void removeTag(Object tag) {
        LegacyContentRegistries.removeCompostableTag(tag);
    }
}
