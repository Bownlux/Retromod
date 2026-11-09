// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.nbt;

import com.mojang.serialization.Codec;
import java.util.Optional;

public final class CompoundTag implements Tag {
    public static final Codec<CompoundTag> CODEC = null;
    public CompoundTag() { throw new UnsupportedOperationException(); }
    public void putInt(String key, int value) { throw new UnsupportedOperationException(); }
    public void putByte(String key, byte value) { throw new UnsupportedOperationException(); }
    public byte getByteOr(String key, byte fallback) { throw new UnsupportedOperationException(); }
    public boolean isEmpty() { throw new UnsupportedOperationException(); }
    public Tag get(String key) { throw new UnsupportedOperationException(); }
    public java.util.Set<String> keySet() { throw new UnsupportedOperationException(); }
    public Tag put(String key, Tag value) { throw new UnsupportedOperationException(); }
    public int getIntOr(String key, int fallback) { throw new UnsupportedOperationException(); }
    public long getLongOr(String key, long fallback) { throw new UnsupportedOperationException(); }
    public Optional<String> getString(String key) { throw new UnsupportedOperationException(); }
    public void putLong(String key, long value) { throw new UnsupportedOperationException(); }
    public void putString(String key, String value) { throw new UnsupportedOperationException(); }
    public ListTag getListOrEmpty(String key) { throw new UnsupportedOperationException(); }
    public Optional<CompoundTag> getCompound(String key) { throw new UnsupportedOperationException(); }
    public boolean contains(String key) { throw new UnsupportedOperationException(); }
    public CompoundTag copy() { throw new UnsupportedOperationException(); }
    public CompoundTag merge(CompoundTag other) { throw new UnsupportedOperationException(); }
}
