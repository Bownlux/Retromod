// Compile-only stub of a Minecraft 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core.component;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;

public class DataComponentInitializers {
    public <T> void add(ResourceKey<T> key, Initializer<T> initializer) { throw new UnsupportedOperationException(); }

    @FunctionalInterface
    public interface Initializer<T> {
        void run(DataComponentMap.Builder builder, HolderLookup.Provider lookup, ResourceKey<T> key);
    }
}
