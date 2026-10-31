// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.nbt;

import java.util.Optional;

public final class CompoundTag implements Tag {
    public CompoundTag() { throw new UnsupportedOperationException(); }
    public void putInt(String key, int value) { throw new UnsupportedOperationException(); }
    public Tag put(String key, Tag value) { throw new UnsupportedOperationException(); }
    public int getIntOr(String key, int fallback) { throw new UnsupportedOperationException(); }
    public ListTag getListOrEmpty(String key) { throw new UnsupportedOperationException(); }
    public Optional<CompoundTag> getCompound(String key) { throw new UnsupportedOperationException(); }
    public boolean contains(String key) { throw new UnsupportedOperationException(); }
}
