/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.tags.TagKey;

import java.util.Optional;

/** The registry tag members 1.21.2 removed, answered by the registry's own tag lookup. */
public final class LegacyRegistryTags {

    private LegacyRegistryTags() {}

    /** {@code registry.getOrCreateTag(tag)}: the bound tag, else one the next tag load fills, else an empty named set. */
    public static <T> HolderSet.Named<T> getOrCreateTag(Registry<T> registry, TagKey<T> tag) {
        // Before the registry freezes its tags are unbound, and asking for one throws.
        try {
            Optional<HolderSet.Named<T>> bound = registry.get(tag);
            if (bound.isPresent()) return bound.get();
        } catch (IllegalStateException unbound) {
            // Fall through to registering the tag.
        }
        // An unfrozen registry keeps a tag asked for during registration, and the tag load binds it.
        if (registry instanceof WritableRegistry<T> writable) {
            try {
                return writable.createRegistrationLookup().getOrThrow(tag);
            } catch (IllegalStateException frozen) {
                // A frozen registry takes no new tags.
            }
        }
        return HolderSet.emptyNamed(registry, tag);
    }

    /** {@code registry.holderOwner()}: the registry is its holders' owner itself now. */
    public static <T> HolderOwner<T> holderOwner(Registry<T> registry) {
        return registry;
    }
}
