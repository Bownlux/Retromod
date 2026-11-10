// Compile-only stub of a fastutil type the 26.x hosts ship (8.5.18), for src/legacyapi; never packaged.
package it.unimi.dsi.fastutil.objects;

public class Reference2ObjectArrayMap<K, V> extends AbstractReference2ObjectMap<K, V> {
    public Reference2ObjectArrayMap() { throw new UnsupportedOperationException(); }
    @Override public V get(Object key) { throw new UnsupportedOperationException(); }
    @Override public int size() { throw new UnsupportedOperationException(); }
    @Override public ObjectSet<Entry<K, V>> reference2ObjectEntrySet() { throw new UnsupportedOperationException(); }
    @Override public V put(K key, V value) { throw new UnsupportedOperationException(); }
}
