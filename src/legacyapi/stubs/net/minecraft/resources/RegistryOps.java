// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.resources;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DynamicOps;
import java.util.stream.Stream;

public class RegistryOps<T> implements DynamicOps<T> {
    @Override public <U> U convertTo(DynamicOps<U> outOps, T input) { throw new UnsupportedOperationException(); }
    @Override public T createMap(Stream<Pair<T, T>> map) { throw new UnsupportedOperationException(); }
}
