// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.entity.living;

import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;

public abstract class MobSpawnEvent {
    private MobSpawnEvent() {}

    public static final class FinalizeSpawn extends MobSpawnEvent {
        public FinalizeSpawn(Mob mob, ServerLevelAccessor level, double x, double y, double z, DifficultyInstance difficulty,
                EntitySpawnReason reason, SpawnGroupData data, ValueInput spawnTag, BaseSpawner spawner) {}
    }
}
