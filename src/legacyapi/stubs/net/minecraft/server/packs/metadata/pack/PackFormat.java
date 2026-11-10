// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.metadata.pack;

// A record on the host.
public final class PackFormat implements Comparable<PackFormat> {
    public static final com.mojang.serialization.Codec<PackFormat> BOTTOM_CODEC = null;
    public static final com.mojang.serialization.Codec<PackFormat> TOP_CODEC = null;
    private PackFormat() {}
    public static PackFormat of(int major, int minor) { throw new UnsupportedOperationException(); }
    public static int lastPreMinorVersion(net.minecraft.server.packs.PackType type) { throw new UnsupportedOperationException(); }
    public int major() { throw new UnsupportedOperationException(); }
    public int minor() { throw new UnsupportedOperationException(); }
    @Override public int compareTo(PackFormat other) { throw new UnsupportedOperationException(); }
}
