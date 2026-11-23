// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs;

public interface PackMetadataResources extends AutoCloseable {
    @Override default void close() { throw new UnsupportedOperationException(); }
}
