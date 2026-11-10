// Compile-only stub of fastutil, as Minecraft 26.3 ships it, for src/legacyapi; never packaged.
package it.unimi.dsi.fastutil.objects;

public interface Object2FloatMap<K> {
    float put(K key, float value);
    float getFloat(Object key);
    boolean containsKey(Object key);
    float removeFloat(Object key);
    float getOrDefault(Object key, float defaultValue);
}
