// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core.component;

import java.util.Set;

public interface DataComponentMap extends DataComponentGetter {
    Set<DataComponentType<?>> keySet();

    static Builder builder() { throw new UnsupportedOperationException(); }

    final class Builder {
        public <T> Builder set(DataComponentType<T> type, T value) { throw new UnsupportedOperationException(); }
        public Builder addAll(DataComponentMap map) { throw new UnsupportedOperationException(); }
        public DataComponentMap build() { throw new UnsupportedOperationException(); }
    }
}
