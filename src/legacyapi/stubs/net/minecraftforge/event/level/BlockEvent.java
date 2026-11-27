// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

public interface BlockEvent {
    LevelAccessor getLevel();
    BlockPos getPos();
    BlockState getState();

    final class BreakEvent implements BlockEvent {
        public BreakEvent(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.player.Player player,
                net.minecraftforge.common.util.Result result) {}
        public LevelAccessor getLevel() { throw new UnsupportedOperationException(); }
        public BlockPos getPos() { throw new UnsupportedOperationException(); }
        public BlockState getState() { throw new UnsupportedOperationException(); }
    }
}
