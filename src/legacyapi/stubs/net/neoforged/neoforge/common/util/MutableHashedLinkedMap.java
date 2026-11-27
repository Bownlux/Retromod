// Compile-only stub of a NeoForge 21.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common.util;

import java.util.Iterator;
import java.util.Map;

public class MutableHashedLinkedMap<K, V> implements Iterable<Map.Entry<K, V>> {
    public MutableHashedLinkedMap() {}
    public V put(K key, V value) { throw new UnsupportedOperationException(); }
    public boolean contains(K key) { throw new UnsupportedOperationException(); }
    public boolean isEmpty() { throw new UnsupportedOperationException(); }
    public V remove(K key) { throw new UnsupportedOperationException(); }
    public V get(K key) { throw new UnsupportedOperationException(); }
    @Override
    public Iterator<Map.Entry<K, V>> iterator() { throw new UnsupportedOperationException(); }
    public V putFirst(K key, V value) { throw new UnsupportedOperationException(); }
    public V putAfter(K after, K key, V value) { throw new UnsupportedOperationException(); }
    public V putBefore(K before, K key, V value) { throw new UnsupportedOperationException(); }
}
