// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockCapability<T, C> extends BaseCapability<T, C> {
    private BlockCapability() { super(null, null, null); }
    public static <T> BlockCapability<T, Direction> createSided(Identifier name, Class<T> type) { throw new UnsupportedOperationException(); }
    public static <T> BlockCapability<T, Void> createVoid(Identifier name, Class<T> type) { throw new UnsupportedOperationException(); }
    public T getCapability(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, C context) { throw new UnsupportedOperationException(); }
}
