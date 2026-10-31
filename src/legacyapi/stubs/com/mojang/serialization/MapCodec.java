// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Function;

public abstract class MapCodec<A> {
    public <S> MapCodec<S> xmap(Function<? super A, ? extends S> to, Function<? super S, ? extends A> from) { throw new UnsupportedOperationException(); }
    public <O> RecordCodecBuilder<O, A> forGetter(Function<O, A> getter) { throw new UnsupportedOperationException(); }
}
