// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

public interface Decoder<A> {
    default <T> DataResult<A> parse(DynamicOps<T> ops, T input) { throw new UnsupportedOperationException(); }
}
