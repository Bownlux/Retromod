// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import java.util.Optional;
import java.util.function.Function;

public interface Codec<A> extends Encoder<A>, Decoder<A> {
    com.mojang.serialization.codecs.PrimitiveCodec<Integer> INT = null;
    com.mojang.serialization.codecs.PrimitiveCodec<Float> FLOAT = null;
    com.mojang.serialization.codecs.PrimitiveCodec<Boolean> BOOL = null;
    static <A> Codec<A> of(Encoder<A> encoder, Decoder<A> decoder) { throw new UnsupportedOperationException(); }
    static <A> Codec<A> lazyInitialized(java.util.function.Supplier<Codec<A>> codec) { throw new UnsupportedOperationException(); }
    static Codec<Integer> intRange(int min, int max) { throw new UnsupportedOperationException(); }
    default MapCodec<A> fieldOf(String name) { throw new UnsupportedOperationException(); }
    default MapCodec<Optional<A>> optionalFieldOf(String name) { throw new UnsupportedOperationException(); }
    default MapCodec<A> optionalFieldOf(String name, A defaultValue) { throw new UnsupportedOperationException(); }
    static <F, S> Codec<com.mojang.datafixers.util.Pair<F, S>> pair(Codec<F> first, Codec<S> second) {
        throw new UnsupportedOperationException();
    }
    static <T> Codec<T> withAlternative(Codec<T> primary, Codec<? extends T> alternative) {
        throw new UnsupportedOperationException();
    }
    default MapCodec<Optional<A>> lenientOptionalFieldOf(String name) { throw new UnsupportedOperationException(); }
    static <F, S> MapCodec<com.mojang.datafixers.util.Pair<F, S>> mapPair(MapCodec<F> first, MapCodec<S> second) {
        throw new UnsupportedOperationException();
    }
    default Codec<java.util.List<A>> listOf() { throw new UnsupportedOperationException(); }
    default Codec<java.util.List<A>> listOf(int minSize, int maxSize) { throw new UnsupportedOperationException(); }
    default <S> Codec<S> xmap(Function<? super A, ? extends S> to, Function<? super S, ? extends A> from) {
        throw new UnsupportedOperationException();
    }
    default <S> Codec<S> flatXmap(Function<? super A, ? extends DataResult<? extends S>> to,
            Function<? super S, ? extends DataResult<? extends A>> from) { throw new UnsupportedOperationException(); }
    default Codec<A> validate(Function<A, DataResult<A>> check) { throw new UnsupportedOperationException(); }
}
