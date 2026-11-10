// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.Optional;
import net.minecraft.resources.ResourceKey;

public interface RegistryAccess extends HolderLookup.Provider {
    <E> Optional<Registry<E>> lookup(ResourceKey<? extends Registry<? extends E>> key);

    interface Frozen extends RegistryAccess {}
}
