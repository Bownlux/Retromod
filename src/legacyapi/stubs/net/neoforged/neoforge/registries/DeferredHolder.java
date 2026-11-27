// Compile-only stub of a NeoForge 1.20.2 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

// Concrete on the host; abstract here so the stub need not answer every Holder method.
public abstract class DeferredHolder<R, T extends R> implements net.minecraft.core.Holder<R> {
    public static <R, T extends R> DeferredHolder<R, T> create(ResourceKey<? extends Registry<R>> registryKey, Identifier id) {
        throw new UnsupportedOperationException();
    }
    public boolean isBound() { throw new UnsupportedOperationException(); }
}
