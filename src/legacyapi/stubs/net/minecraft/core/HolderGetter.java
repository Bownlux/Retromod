// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

public interface HolderGetter<T> {
    Optional<Holder.Reference<T>> get(ResourceKey<T> key);
    Optional<HolderSet.Named<T>> get(TagKey<T> tag);

    interface Provider {
        default <T> Optional<Holder.Reference<T>> get(ResourceKey<T> key) { throw new UnsupportedOperationException(); }
    }
}
