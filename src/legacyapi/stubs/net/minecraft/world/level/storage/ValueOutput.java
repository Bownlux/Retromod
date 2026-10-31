// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

public interface ValueOutput {
    void putInt(String key, int value);
    <T> void store(String key, Codec<T> codec, T value);
    <T> void store(MapCodec<T> codec, T value);
    <T> TypedOutputList<T> list(String key, Codec<T> codec);
    ValueOutputList childrenList(String key);
    interface TypedOutputList<T> {
        void add(T value);
    }
    interface ValueOutputList {
        ValueOutput addChild();
    }
}
