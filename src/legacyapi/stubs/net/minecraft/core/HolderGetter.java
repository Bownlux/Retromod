// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

public interface HolderGetter<T> {
    Optional<Holder.Reference<T>> get(ResourceKey<T> key);
    Optional<HolderSet.Named<T>> get(TagKey<T> tag);
    // A default method on the host.
    default HolderSet.Named<T> getOrThrow(TagKey<T> tag) { throw new UnsupportedOperationException(); }

    interface Provider {
        default <T> Optional<Holder.Reference<T>> get(ResourceKey<T> key) { throw new UnsupportedOperationException(); }
        // Abstract on the host, returning Optional<? extends HolderGetter<T>>; looser here, since the Registry stub is no getter.
        default <T> Optional<?> lookup(ResourceKey<? extends Registry<? extends T>> key) { throw new UnsupportedOperationException(); }
    }
}
