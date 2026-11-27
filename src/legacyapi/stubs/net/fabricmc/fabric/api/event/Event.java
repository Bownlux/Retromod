// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.event;

import net.minecraft.resources.Identifier;

public abstract class Event<T> {
    public static final Identifier DEFAULT_PHASE = null;
    protected Event() {}
    public abstract void register(T listener);
    public void register(Identifier phase, T listener) { throw new UnsupportedOperationException(); }
    public final T invoker() { throw new UnsupportedOperationException(); }
}
