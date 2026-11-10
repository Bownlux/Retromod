// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface DataResult<R> {
    static <R> DataResult<R> success(R result) { throw new UnsupportedOperationException(); }
    static <R> DataResult<R> error(Supplier<String> message) { throw new UnsupportedOperationException(); }
    Optional<R> result();
    Optional<R> resultOrPartial(Consumer<String> onError);
    default R getOrThrow() { throw new UnsupportedOperationException(); }
    default <E extends Throwable> R getOrThrow(java.util.function.Function<String, E> exceptionSupplier) throws E { throw new UnsupportedOperationException(); }
}
