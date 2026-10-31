// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.serialization;

import java.util.Optional;

public interface Codec<A> extends Encoder<A>, Decoder<A> {
    default MapCodec<Optional<A>> optionalFieldOf(String name) { throw new UnsupportedOperationException(); }
}
