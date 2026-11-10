// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import java.util.Optional;
import java.util.function.Function;

public interface Codec<A> extends Encoder<A>, Decoder<A> {
    com.mojang.serialization.codecs.PrimitiveCodec<Integer> INT = null;
    default MapCodec<A> fieldOf(String name) { throw new UnsupportedOperationException(); }
    default MapCodec<Optional<A>> optionalFieldOf(String name) { throw new UnsupportedOperationException(); }
    default MapCodec<Optional<A>> lenientOptionalFieldOf(String name) { throw new UnsupportedOperationException(); }
    static <F, S> MapCodec<com.mojang.datafixers.util.Pair<F, S>> mapPair(MapCodec<F> first, MapCodec<S> second) {
        throw new UnsupportedOperationException();
    }
    default <S> Codec<S> xmap(Function<? super A, ? extends S> to, Function<? super S, ? extends A> from) {
        throw new UnsupportedOperationException();
    }
    default <S> Codec<S> flatXmap(Function<? super A, ? extends DataResult<? extends S>> to,
            Function<? super S, ? extends DataResult<? extends A>> from) { throw new UnsupportedOperationException(); }
}
