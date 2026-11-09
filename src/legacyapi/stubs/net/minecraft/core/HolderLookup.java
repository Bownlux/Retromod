// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

public interface HolderLookup<T> extends HolderGetter<T> {
    Stream<Holder.Reference<T>> listElements();
    Stream<HolderSet.Named<T>> listTags();

    interface RegistryLookup<T> extends HolderLookup<T> {
        ResourceKey<? extends Registry<? extends T>> key();
        Lifecycle registryLifecycle();
        default RegistryLookup<T> filterElements(Predicate<T> filter) { throw new UnsupportedOperationException(); }
    }

    interface Provider extends HolderGetter.Provider {
        default <V> RegistryOps<V> createSerializationContext(DynamicOps<V> ops) { throw new UnsupportedOperationException(); }
        default <T> RegistryLookup<T> lookupOrThrow(ResourceKey<? extends Registry<? extends T>> key) { throw new UnsupportedOperationException(); }
    }
}
