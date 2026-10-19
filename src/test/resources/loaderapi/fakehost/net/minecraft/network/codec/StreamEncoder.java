package net.minecraft.network.codec;

public interface StreamEncoder<B, V> {
    void encode(B buffer, V value);
}
