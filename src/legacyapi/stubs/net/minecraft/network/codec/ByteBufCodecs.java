// Compile-only stub of a 1.20.5 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;

public interface ByteBufCodecs {
    StreamCodec<ByteBuf, CompoundTag> COMPOUND_TAG = null;
}
