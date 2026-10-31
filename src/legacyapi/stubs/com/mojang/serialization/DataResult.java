// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import java.util.Optional;

public interface DataResult<R> {
    Optional<R> result();
    default R getOrThrow() { throw new UnsupportedOperationException(); }
}
