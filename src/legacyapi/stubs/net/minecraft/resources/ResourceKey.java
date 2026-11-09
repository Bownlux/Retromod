// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.resources;

import net.minecraft.core.Registry;

public final class ResourceKey<T> {
    public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, Identifier location) { throw new UnsupportedOperationException(); }
    public Identifier identifier() { throw new UnsupportedOperationException(); }
}
