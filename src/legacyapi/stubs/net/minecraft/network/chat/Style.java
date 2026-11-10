// Compile-only stub of a host type for src/legacyapi; never packaged.
package net.minecraft.network.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

public final class Style {
    public boolean isEmpty() { throw new UnsupportedOperationException(); }

    public static class Serializer {
        public static final MapCodec<Style> MAP_CODEC = null;
        public static final Codec<Style> CODEC = null;
    }
}
