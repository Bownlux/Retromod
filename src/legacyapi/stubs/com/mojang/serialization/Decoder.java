// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

public interface Decoder<A> {
    <T> DataResult<com.mojang.datafixers.util.Pair<A, T>> decode(DynamicOps<T> ops, T input);
    default <T> DataResult<A> parse(DynamicOps<T> ops, T input) { throw new UnsupportedOperationException(); }
}
