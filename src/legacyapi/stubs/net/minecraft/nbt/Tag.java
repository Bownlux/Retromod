// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.nbt;

import java.util.Optional;

public interface Tag {
    default Optional<Integer> asInt() { throw new UnsupportedOperationException(); }
}
