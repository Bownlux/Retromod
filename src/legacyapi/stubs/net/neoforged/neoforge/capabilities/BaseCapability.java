// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import net.minecraft.resources.Identifier;

public abstract class BaseCapability<T, C> {
    protected BaseCapability(Identifier name, Class<T> type, Class<C> context) { throw new UnsupportedOperationException(); }
    public final Identifier name() { throw new UnsupportedOperationException(); }
    public final Class<T> typeClass() { throw new UnsupportedOperationException(); }
    public final Class<C> contextClass() { throw new UnsupportedOperationException(); }
}
