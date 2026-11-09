/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import java.util.stream.Stream;

/** Stands in for DataFixerUpper's DynamicOps: the two members the ingredient codec calls. */
public interface DynamicOps<T> {
    <U> U convertTo(DynamicOps<U> outOps, T input);

    T createMap(Stream<Pair<T, T>> map);
}
