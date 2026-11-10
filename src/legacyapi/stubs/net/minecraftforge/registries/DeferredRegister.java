// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

public class DeferredRegister<T> {
    public static <B> DeferredRegister<B> create(IForgeRegistry<B> registry, String modid) { throw new UnsupportedOperationException(); }
    public static <B> DeferredRegister<B> createOptional(ResourceKey<? extends Registry<B>> key, String modid) { throw new UnsupportedOperationException(); }
}
