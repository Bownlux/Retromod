// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.codec;

public interface StreamEncoder<O, T> {
    void encode(O output, T value);
}
