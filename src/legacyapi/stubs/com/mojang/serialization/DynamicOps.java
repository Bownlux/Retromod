// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import com.mojang.datafixers.util.Pair;
import java.util.stream.Stream;

public interface DynamicOps<T> {
    <U> U convertTo(DynamicOps<U> outOps, T input);
    T createMap(Stream<Pair<T, T>> map);
}
