// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;

public class Level {
    public <T, C> T getCapability(BlockCapability<T, C> capability, BlockPos pos, C context) { throw new UnsupportedOperationException(); }
    public <T, C> T getCapability(BlockCapability<T, C> capability, BlockPos pos, BlockState state, BlockEntity blockEntity, C context) { throw new UnsupportedOperationException(); }
    public MinecraftServer getServer() { throw new UnsupportedOperationException(); }
}
