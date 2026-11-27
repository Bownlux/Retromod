// Compile-only stub of a 26.x host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

import com.mojang.datafixers.util.Either;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

public abstract class BaseSpawner {
    // NeoForge's IOwnedSpawner, which BaseSpawner implements on NeoForge 26.x; Forge has no such member.
    public Either<BlockEntity, Entity> getOwner() { throw new UnsupportedOperationException(); }
}
