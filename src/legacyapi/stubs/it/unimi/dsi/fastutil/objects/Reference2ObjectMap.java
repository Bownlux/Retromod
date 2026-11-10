// Compile-only stub of a fastutil type the 26.x hosts ship (8.5.18), for src/legacyapi; never packaged.
package it.unimi.dsi.fastutil.objects;

public interface Reference2ObjectMap<K, V> extends Reference2ObjectFunction<K, V>, java.util.Map<K, V> {
    @Override V get(Object key);
    @Override int size();
    @Override boolean containsKey(Object key);
    @Override ReferenceSet<K> keySet();
    @Override ObjectCollection<V> values();
    ObjectSet<Entry<K, V>> reference2ObjectEntrySet();

    interface Entry<K, V> extends java.util.Map.Entry<K, V> {}
}
