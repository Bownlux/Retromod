// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

public interface ServerLevelAccessor extends LevelAccessor {
    net.minecraft.world.DifficultyInstance getCurrentDifficultyAt(net.minecraft.core.BlockPos pos);
}
