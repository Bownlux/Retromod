// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

public interface RecordBuilder<T> {
    default RecordBuilder<T> add(String key, T value) { throw new UnsupportedOperationException(); }
    RecordBuilder<T> withErrorsFrom(DataResult<?> result);
}
