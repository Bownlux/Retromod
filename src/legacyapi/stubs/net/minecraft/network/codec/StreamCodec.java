// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.codec;

public interface StreamCodec<B, V> extends StreamEncoder<B, V>, StreamDecoder<B, V> {
    static <B, V> StreamCodec<B, V> of(StreamEncoder<B, V> encoder, StreamDecoder<B, V> decoder) { throw new UnsupportedOperationException(); }
}
