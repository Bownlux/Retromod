// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

public interface Holder<T> {
    T value();

    static <T> Holder<T> direct(T value) { throw new UnsupportedOperationException(); }
}
