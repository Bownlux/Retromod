// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization.codecs;

import com.mojang.datafixers.Products;
import com.mojang.datafixers.kinds.App;

public final class RecordCodecBuilder<O, F> implements App<RecordCodecBuilder.Mu<O>, F> {
    public static final class Mu<O> {
    }

    public static final class Instance<O> {
        public <T1> Products.P1<Mu<O>, T1> group(App<Mu<O>, T1> t1) { throw new UnsupportedOperationException(); }
    }
}
