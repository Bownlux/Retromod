// Compile-only stub of fastutil, as Minecraft 26.3 ships it, for src/legacyapi; never packaged.
package it.unimi.dsi.fastutil.objects;

public class Object2FloatOpenHashMap<K> implements Object2FloatMap<K> {
    public Object2FloatOpenHashMap() { throw new UnsupportedOperationException(); }
    public float put(K key, float value) { throw new UnsupportedOperationException(); }
    public float getFloat(Object key) { throw new UnsupportedOperationException(); }
    public boolean containsKey(Object key) { throw new UnsupportedOperationException(); }
    public float removeFloat(Object key) { throw new UnsupportedOperationException(); }
    public float getOrDefault(Object key, float defaultValue) { throw new UnsupportedOperationException(); }
    public float putIfAbsent(K key, float value) { throw new UnsupportedOperationException(); }
    public float addTo(K key, float increment) { throw new UnsupportedOperationException(); }
    public float merge(K key, float value,
            java.util.function.BiFunction<? super Float, ? super Float, ? extends Float> remapping) {
        throw new UnsupportedOperationException();
    }
    public float defaultReturnValue() { throw new UnsupportedOperationException(); }
}
