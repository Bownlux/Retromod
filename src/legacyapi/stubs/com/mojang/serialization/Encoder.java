// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

public interface Encoder<A> {
    default <T> DataResult<T> encodeStart(DynamicOps<T> ops, A input) { throw new UnsupportedOperationException(); }
}
