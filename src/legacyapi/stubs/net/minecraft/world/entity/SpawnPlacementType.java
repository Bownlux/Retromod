// Compile-only stub of a 1.20.5 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;

public interface SpawnPlacementType {
    boolean isSpawnPositionOk(LevelReader level, BlockPos pos, EntityType<?> type);
}
