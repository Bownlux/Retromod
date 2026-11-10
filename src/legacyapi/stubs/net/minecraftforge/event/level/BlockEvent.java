// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

public interface BlockEvent {
    LevelAccessor getLevel();
    BlockPos getPos();
    BlockState getState();
}
