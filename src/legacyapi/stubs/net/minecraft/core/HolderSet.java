// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.List;
import java.util.stream.Stream;

public interface HolderSet<T> extends Iterable<Holder<T>> {
    Stream<Holder<T>> stream();
    int size();
    boolean isBound();
    Holder<T> get(int index);
    boolean contains(Holder<T> holder);
    java.util.Optional<net.minecraft.tags.TagKey<T>> unwrapKey();
    java.util.Optional<Holder<T>> getRandomElement(net.minecraft.util.RandomSource random);

    static <T> Named<T> emptyNamed(HolderOwner<T> owner, net.minecraft.tags.TagKey<T> key) { throw new UnsupportedOperationException(); }

    static <T> Direct<T> direct(List<? extends Holder<T>> holders) { throw new UnsupportedOperationException(); }

    abstract class Direct<T> implements HolderSet<T> {
    }

    abstract class Named<T> implements HolderSet<T> {
    }
}
