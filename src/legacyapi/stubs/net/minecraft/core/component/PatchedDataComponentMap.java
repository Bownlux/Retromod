// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core.component;

import java.util.Set;

public final class PatchedDataComponentMap implements DataComponentMap {
    public PatchedDataComponentMap(DataComponentMap prototype) { throw new UnsupportedOperationException(); }
    public static PatchedDataComponentMap fromPatch(DataComponentMap prototype, DataComponentPatch patch) {
        throw new UnsupportedOperationException();
    }
    @Override public <T> T get(DataComponentType<? extends T> type) { throw new UnsupportedOperationException(); }
    @Override public Set<DataComponentType<?>> keySet() { throw new UnsupportedOperationException(); }
}
