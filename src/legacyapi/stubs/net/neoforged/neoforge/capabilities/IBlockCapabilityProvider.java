// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public interface IBlockCapabilityProvider<T, C> {
    T getCapability(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, C context);
}
