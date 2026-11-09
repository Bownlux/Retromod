// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.Optional;
import net.minecraft.resources.ResourceKey;

public interface Holder<T> {
    T value();

    boolean is(ResourceKey<T> key);

    Optional<ResourceKey<T>> unwrapKey();
    java.util.stream.Stream<net.minecraft.tags.TagKey<T>> tags();

    static <T> Holder<T> direct(T value) { throw new UnsupportedOperationException(); }

    class Reference<T> implements Holder<T> {
        public static <T> Reference<T> createStandAlone(HolderOwner<T> owner, ResourceKey<T> key) {
            throw new UnsupportedOperationException();
        }
        public T value() { throw new UnsupportedOperationException(); }
        public boolean is(ResourceKey<T> key) { throw new UnsupportedOperationException(); }
        public Optional<ResourceKey<T>> unwrapKey() { throw new UnsupportedOperationException(); }
        public java.util.stream.Stream<net.minecraft.tags.TagKey<T>> tags() { throw new UnsupportedOperationException(); }
        public boolean areComponentsBound() { throw new UnsupportedOperationException(); }
        public net.minecraft.core.component.DataComponentMap components() { throw new UnsupportedOperationException(); }
        public net.minecraft.resources.ResourceKey<T> key() { throw new UnsupportedOperationException(); }
    }
}
