// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.resources;

import net.minecraft.core.Registry;

public final class ResourceKey<T> {
    public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, Identifier location) { throw new UnsupportedOperationException(); }
    public ResourceKey<Registry<T>> registryKey() { throw new UnsupportedOperationException(); }
    public Identifier identifier() { throw new UnsupportedOperationException(); }
    // 1.20.5 to 1.21.10 shape.
    public ResourceLocation location() { throw new UnsupportedOperationException(); }
}
