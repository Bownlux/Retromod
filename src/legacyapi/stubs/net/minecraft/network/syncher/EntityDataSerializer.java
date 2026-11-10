// Compile-only stub of a 1.20.5 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.syncher;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public interface EntityDataSerializer<T> {
    StreamCodec<? super RegistryFriendlyByteBuf, T> codec();
    T copy(T value);
}
