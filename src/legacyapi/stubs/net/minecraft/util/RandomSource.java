// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.util;

public interface RandomSource {
    static RandomSource create() { throw new UnsupportedOperationException(); }
    static RandomSource create(long seed) { throw new UnsupportedOperationException(); }
    void setSeed(long seed);
    long nextLong();
    int nextInt(int bound);
}
