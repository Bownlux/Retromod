// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Function;
import java.util.stream.Stream;

public abstract class MapCodec<A> {
    public MapCodec() {}
    public static <A> MapCodec<A> unit(A value) { throw new UnsupportedOperationException(); }
    public static <A> MapCodec<A> assumeMapUnsafe(Codec<A> codec) { throw new UnsupportedOperationException(); }
    public abstract <T> Stream<T> keys(DynamicOps<T> ops);
    public abstract <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input);
    public abstract <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix);
    public <S> MapCodec<S> xmap(Function<? super A, ? extends S> to, Function<? super S, ? extends A> from) { throw new UnsupportedOperationException(); }
    public <O> RecordCodecBuilder<O, A> forGetter(Function<O, A> getter) { throw new UnsupportedOperationException(); }
    public MapCodec<A> orElse(A value) { throw new UnsupportedOperationException(); }
    public Codec<A> codec() { throw new UnsupportedOperationException(); }

    public static final class MapCodecCodec<A> {
        public MapCodec<A> codec() { throw new UnsupportedOperationException(); }
    }
}
