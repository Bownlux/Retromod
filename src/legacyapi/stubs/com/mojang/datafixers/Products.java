// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.datafixers;

import com.mojang.datafixers.kinds.App;
import com.mojang.datafixers.kinds.Applicative;
import com.mojang.datafixers.util.Function4;
import com.mojang.datafixers.util.Function5;
import com.mojang.datafixers.util.Function9;
import java.util.function.BiFunction;

public interface Products {
    final class P1<F, T1> {
    }

    final class P4<F, T1, T2, T3, T4> {
        public P4(App<F, T1> t1, App<F, T2> t2, App<F, T3> t3, App<F, T4> t4) { throw new UnsupportedOperationException(); }
        public App<F, T1> t1() { throw new UnsupportedOperationException(); }
        public App<F, T2> t2() { throw new UnsupportedOperationException(); }
        public App<F, T3> t3() { throw new UnsupportedOperationException(); }
        public App<F, T4> t4() { throw new UnsupportedOperationException(); }
        public <R> App<F, R> apply(Applicative<F, ?> instance, Function4<T1, T2, T3, T4, R> function) {
            throw new UnsupportedOperationException();
        }
    }

    final class P5<F, T1, T2, T3, T4, T5> {
        public <R> App<F, R> apply(Applicative<F, ?> instance, Function5<T1, T2, T3, T4, T5, R> function) {
            throw new UnsupportedOperationException();
        }
    }

    final class P2<F, T1, T2> {
        public <R> App<F, R> apply(Applicative<F, ?> instance, BiFunction<T1, T2, R> function) {
            throw new UnsupportedOperationException();
        }
    }

    final class P9<F, T1, T2, T3, T4, T5, T6, T7, T8, T9> {
        public <R> App<F, R> apply(Applicative<F, ?> instance, Function9<T1, T2, T3, T4, T5, T6, T7, T8, T9, R> function) {
            throw new UnsupportedOperationException();
        }
    }
}
