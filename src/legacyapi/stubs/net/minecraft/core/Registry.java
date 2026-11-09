// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public interface Registry<T> extends Iterable<T> {
    T getValue(Identifier id);
    Identifier getKey(T value);
    boolean containsKey(Identifier id);
    Set<Identifier> keySet();
    Set<Map.Entry<ResourceKey<T>, T>> entrySet();
    Optional<Holder.Reference<T>> get(ResourceKey<T> key);
    Optional<Holder.Reference<T>> get(Identifier id);
    int size();
    default T getValueOrThrow(ResourceKey<T> key) { throw new UnsupportedOperationException(); }
}
