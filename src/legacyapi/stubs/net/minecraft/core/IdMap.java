// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

public interface IdMap<T> extends Iterable<T> {
    T byId(int id);
    // A default method on the host.
    default int getIdOrThrow(T value) { throw new UnsupportedOperationException(); }
}
