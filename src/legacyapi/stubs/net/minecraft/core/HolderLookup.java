// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import com.mojang.serialization.DynamicOps;
import net.minecraft.resources.RegistryOps;

public interface HolderLookup {
    interface Provider {
        default <V> RegistryOps<V> createSerializationContext(DynamicOps<V> ops) { throw new UnsupportedOperationException(); }
    }
}
