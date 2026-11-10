/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import java.util.stream.Stream;

/** Stands in for DataFixerUpper's MapCodec, which the ingredient codec extends. */
public abstract class MapCodec<A> {
    public MapCodec() {}

    public abstract <T> Stream<T> keys(DynamicOps<T> ops);

    public abstract <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input);

    public abstract <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix);
}
