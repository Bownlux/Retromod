// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.List;
import java.util.stream.Stream;

public interface HolderSet<T> {
    Stream<Holder<T>> stream();

    static <T> Direct<T> direct(List<? extends Holder<T>> holders) { throw new UnsupportedOperationException(); }

    abstract class Direct<T> implements HolderSet<T> {
    }
}
