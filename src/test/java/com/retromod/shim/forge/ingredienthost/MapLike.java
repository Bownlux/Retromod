/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import java.util.stream.Stream;

/** Stands in for DataFixerUpper's MapLike: a map's entries. */
public interface MapLike<T> {
    Stream<Pair<T, T>> entries();
}
