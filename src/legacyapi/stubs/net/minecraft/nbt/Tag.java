// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.nbt;

import java.util.Optional;

public interface Tag {
    default Optional<Integer> asInt() { throw new UnsupportedOperationException(); }
    Tag copy();
    // 1.20.5 to 1.21.4 shape.
    default String getAsString() { throw new UnsupportedOperationException(); }
}
