// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.event;

public abstract class Event<T> {
    protected Event() {}
    public void register(T listener) { throw new UnsupportedOperationException(); }
}
