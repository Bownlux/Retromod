// Compile-only stub of a fastutil type the 26.x hosts ship (8.5.18), for src/legacyapi; never packaged.
package it.unimi.dsi.fastutil.objects;

import java.util.Map;

public abstract class AbstractReference2ObjectMap<K, V> implements Reference2ObjectMap<K, V> {
    protected AbstractReference2ObjectMap() {}
    @Override public boolean containsKey(Object key) { throw new UnsupportedOperationException(); }
    @Override public boolean containsValue(Object value) { throw new UnsupportedOperationException(); }
    @Override public boolean isEmpty() { throw new UnsupportedOperationException(); }
    @Override public ReferenceSet<K> keySet() { throw new UnsupportedOperationException(); }
    @Override public ObjectCollection<V> values() { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<? extends K, ? extends V> from) { throw new UnsupportedOperationException(); }
    // The real hierarchy answers these through fastutil's interface defaults.
    @Override public V put(K key, V value) { throw new UnsupportedOperationException(); }
    @Override public V remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public java.util.Set<Map.Entry<K, V>> entrySet() { throw new UnsupportedOperationException(); }
}
