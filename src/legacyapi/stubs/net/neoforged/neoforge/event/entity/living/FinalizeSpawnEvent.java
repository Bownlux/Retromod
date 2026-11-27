// Compile-only stub of a NeoForge 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.event.entity.living;

import com.mojang.datafixers.util.Either;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.Event;

public class FinalizeSpawnEvent extends Event {
    public FinalizeSpawnEvent(Mob mob, ServerLevelAccessor level, double x, double y, double z, DifficultyInstance difficulty,
            EntitySpawnReason reason, SpawnGroupData data, Either<BlockEntity, Entity> spawner) {}
}
