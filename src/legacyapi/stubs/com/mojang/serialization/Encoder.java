// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

public interface Encoder<A> {
    <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix);
    default <T> DataResult<T> encodeStart(DynamicOps<T> ops, A input) { throw new UnsupportedOperationException(); }
}
