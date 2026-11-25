// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.level;

public interface ChunkResult<T> {
    boolean isSuccess();
    T orElse(T fallback);
}
