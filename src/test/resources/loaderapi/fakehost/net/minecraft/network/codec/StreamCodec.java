package net.minecraft.network.codec;

public interface StreamCodec<B, V> extends StreamDecoder<B, V>, StreamEncoder<B, V> {
    @SuppressWarnings("unchecked")
    default <S extends B> StreamCodec<S, V> cast() { return (StreamCodec<S, V>) this; }
}
