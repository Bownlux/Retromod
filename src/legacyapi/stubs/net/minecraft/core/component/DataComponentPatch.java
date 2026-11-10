// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core.component;

public final class DataComponentPatch {
    public SplitResult split() { throw new UnsupportedOperationException(); }

    // A record on the host.
    public static final class SplitResult {
        private SplitResult() {}
        public DataComponentMap added() { throw new UnsupportedOperationException(); }
        public java.util.Set<DataComponentType<?>> removed() { throw new UnsupportedOperationException(); }
    }
}
