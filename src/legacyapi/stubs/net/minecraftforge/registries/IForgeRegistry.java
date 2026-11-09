// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.registries;

import com.mojang.serialization.Codec;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.tags.ITagManager;

public interface IForgeRegistry<V> extends Iterable<V> {
    ResourceKey<Registry<V>> getRegistryKey();
    Identifier getRegistryName();
    void register(String key, V value);
    void register(Identifier key, V value);
    boolean containsKey(Identifier key);
    boolean containsValue(V value);
    boolean isEmpty();
    V getValue(Identifier key);
    Identifier getKey(V value);
    Identifier getDefaultKey();
    Optional<ResourceKey<V>> getResourceKey(V value);
    Set<Identifier> getKeys();
    Collection<V> getValues();
    Set<Map.Entry<ResourceKey<V>, V>> getEntries();
    Codec<V> getCodec();
    Optional<Holder<V>> getHolder(ResourceKey<V> key);
    Optional<Holder<V>> getHolder(Identifier location);
    Optional<Holder<V>> getHolder(V value);
    ITagManager<V> tags();
    Optional<Holder.Reference<V>> getDelegate(ResourceKey<V> rkey);
    Holder.Reference<V> getDelegateOrThrow(ResourceKey<V> rkey);
    Optional<Holder.Reference<V>> getDelegate(Identifier key);
    Holder.Reference<V> getDelegateOrThrow(Identifier key);
    Optional<Holder.Reference<V>> getDelegate(V value);
    Holder.Reference<V> getDelegateOrThrow(V value);
    <T> T getSlaveMap(SlaveKey<T> slaveMapName);

    record SlaveKey<T>(Identifier name) {}
}
