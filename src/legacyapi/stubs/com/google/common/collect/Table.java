// Compile-only stub of the Guava type Minecraft ships, for src/legacyapi; never packaged.
package com.google.common.collect;

public interface Table<R, C, V> {
    V put(R rowKey, C columnKey, V value);
}
