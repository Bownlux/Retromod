package net.minecraft.network.codec;

public interface StreamDecoder<B, V> {
    V decode(B buffer);
}
