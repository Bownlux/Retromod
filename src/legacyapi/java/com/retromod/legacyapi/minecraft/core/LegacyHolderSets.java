/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;

import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** An old mod's reads of a tag's holder set as 1.21.1 answered them: a tag that is not bound yet is empty. */
public final class LegacyHolderSets {

    private LegacyHolderSets() {}

    // Each read asks the set first, since 1.21.2 and newer throw "Trying to access unbound tag" for an unbound one.

    public static <T> Iterator<Holder<T>> iterator(HolderSet<T> set) {
        return set.isBound() ? set.iterator() : Collections.emptyIterator();
    }

    public static <T> Stream<Holder<T>> stream(HolderSet<T> set) {
        return set.isBound() ? set.stream() : Stream.empty();
    }

    public static <T> int size(HolderSet<T> set) {
        return set.isBound() ? set.size() : 0;
    }

    /** An unbound set has no element at any index, as the old empty list had none. */
    public static <T> Holder<T> get(HolderSet<T> set, int index) {
        if (!set.isBound()) throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length 0");
        return set.get(index);
    }

    public static <T> Optional<Holder<T>> getRandomElement(HolderSet<T> set, RandomSource random) {
        return set.isBound() ? set.getRandomElement(random) : Optional.empty();
    }

    /** A tag's set asks the holder whether it carries the tag, which answers "no" before tags are bound, as 1.21.1 did. */
    public static <T> boolean contains(HolderSet<T> set, Holder<T> holder) {
        Optional<TagKey<T>> key = set.unwrapKey();
        if (key.isPresent()) return LegacyHolderTags.is(holder, key.get());
        return set.contains(holder);
    }

    public static <T> void forEach(HolderSet<T> set, Consumer<? super Holder<T>> action) {
        if (set.isBound()) set.forEach(action);
    }
}
