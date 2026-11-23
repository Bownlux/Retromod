// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.nbt;

public class NbtIo {
    public static CompoundTag readCompressed(java.nio.file.Path file, NbtAccounter accounter) throws java.io.IOException {
        throw new UnsupportedOperationException();
    }
    public static CompoundTag readCompressed(java.io.InputStream stream, NbtAccounter accounter) throws java.io.IOException {
        throw new UnsupportedOperationException();
    }
    public static void writeCompressed(CompoundTag tag, java.nio.file.Path file) throws java.io.IOException {
        throw new UnsupportedOperationException();
    }
    public static CompoundTag read(java.nio.file.Path file) throws java.io.IOException { throw new UnsupportedOperationException(); }
    public static void write(CompoundTag tag, java.nio.file.Path file) throws java.io.IOException { throw new UnsupportedOperationException(); }
}
