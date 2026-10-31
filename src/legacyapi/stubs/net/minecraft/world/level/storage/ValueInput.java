// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Optional;

public interface ValueInput {
    int getIntOr(String key, int fallback);
    <T> Optional<T> read(String key, Codec<T> codec);
    <T> Optional<T> read(MapCodec<T> codec);
    <T> TypedInputList<T> listOrEmpty(String key, Codec<T> codec);
    ValueInputList childrenListOrEmpty(String key);
    interface TypedInputList<T> extends Iterable<T> {}
    interface ValueInputList extends Iterable<ValueInput> {}
}
