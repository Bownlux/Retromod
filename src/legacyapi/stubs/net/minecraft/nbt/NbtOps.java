// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.nbt;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DynamicOps;
import java.util.stream.Stream;

public class NbtOps implements DynamicOps<Tag> {
    public static final NbtOps INSTANCE = null;
    @Override public <U> U convertTo(DynamicOps<U> outOps, Tag input) { throw new UnsupportedOperationException(); }
    @Override public Tag createMap(Stream<Pair<Tag, Tag>> map) { throw new UnsupportedOperationException(); }
}
