// Compile-only stub of a DataFixerUpper type the 26.2 and 26.3 hosts ship, for src/legacyapi; never packaged.
package com.mojang.datafixers.util;

import java.util.Optional;

public abstract class Either<L, R> {
    private Either() {}
    public abstract Optional<L> left();
    public abstract Optional<R> right();
}
