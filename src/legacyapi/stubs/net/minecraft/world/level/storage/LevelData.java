// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage;

public interface LevelData {
    // Abstract on the host; a default here keeps the stub implementations compiling.
    default long getGameTime() { throw new UnsupportedOperationException(); }
    // A record on the host.
    final class RespawnData {
        public static RespawnData of(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                net.minecraft.core.BlockPos pos, float yaw, float pitch) { throw new UnsupportedOperationException(); }
        public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() { throw new UnsupportedOperationException(); }
        public net.minecraft.core.BlockPos pos() { throw new UnsupportedOperationException(); }
        public float yaw() { throw new UnsupportedOperationException(); }
    }
}
