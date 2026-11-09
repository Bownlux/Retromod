// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization.codecs;

import com.mojang.datafixers.Products;
import com.mojang.datafixers.kinds.App;
import com.mojang.datafixers.kinds.Applicative;
import com.mojang.serialization.Codec;
import java.util.function.Function;

public final class RecordCodecBuilder<O, F> implements App<RecordCodecBuilder.Mu<O>, F> {
    public static final class Mu<O> {
    }

    public static <O> Codec<O> create(Function<Instance<O>, ? extends App<Mu<O>, O>> builder) {
        throw new UnsupportedOperationException();
    }

    public static final class Instance<O> implements Applicative<Mu<O>, Instance.Mu<O>> {
        public static final class Mu<O> {
        }

        public <T1> Products.P1<RecordCodecBuilder.Mu<O>, T1> group(App<RecordCodecBuilder.Mu<O>, T1> t1) { throw new UnsupportedOperationException(); }

        public <T, R> App<RecordCodecBuilder.Mu<O>, R> map(Function<? super T, ? extends R> function, App<RecordCodecBuilder.Mu<O>, T> value) {
            throw new UnsupportedOperationException();
        }

        public <T1, T2, T3, T4> Products.P4<RecordCodecBuilder.Mu<O>, T1, T2, T3, T4> group(App<RecordCodecBuilder.Mu<O>, T1> t1,
                App<RecordCodecBuilder.Mu<O>, T2> t2, App<RecordCodecBuilder.Mu<O>, T3> t3, App<RecordCodecBuilder.Mu<O>, T4> t4) {
            throw new UnsupportedOperationException();
        }

        public <T1, T2, T3, T4, T5> Products.P5<RecordCodecBuilder.Mu<O>, T1, T2, T3, T4, T5> group(App<RecordCodecBuilder.Mu<O>, T1> t1,
                App<RecordCodecBuilder.Mu<O>, T2> t2, App<RecordCodecBuilder.Mu<O>, T3> t3, App<RecordCodecBuilder.Mu<O>, T4> t4,
                App<RecordCodecBuilder.Mu<O>, T5> t5) {
            throw new UnsupportedOperationException();
        }
    }
}
