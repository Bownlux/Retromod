// Compile-only stub of the fastutil type the hosts ship, for src/legacyapi; never packaged.
package it.unimi.dsi.fastutil.objects;

public interface Object2IntMap<K> {
    interface Entry<K> extends java.util.Map.Entry<K, Integer> {
        int getIntValue();
    }
}
