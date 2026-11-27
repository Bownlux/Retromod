// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage;

public class LevelStorageSource {
    public LevelCandidates findLevelCandidates() throws LevelStorageException { throw new UnsupportedOperationException(); }
    public static final class LevelCandidates {
        public java.util.List<LevelDirectory> levels() { throw new UnsupportedOperationException(); }
    }
    public static final class LevelDirectory {}
    public class LevelStorageAccess {
        public com.mojang.serialization.Dynamic<?> getUnfixedDataTag(boolean useFallback) throws java.io.IOException { throw new UnsupportedOperationException(); }
    }
}
