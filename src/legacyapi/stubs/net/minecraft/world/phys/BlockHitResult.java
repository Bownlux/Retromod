// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.phys;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public class BlockHitResult {
    // The host's codec reads any netty ByteBuf, which FriendlyByteBuf is.
    public static final StreamCodec<? super FriendlyByteBuf, BlockHitResult> STREAM_CODEC = null;
}
