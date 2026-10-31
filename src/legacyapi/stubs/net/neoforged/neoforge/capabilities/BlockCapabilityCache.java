// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class BlockCapabilityCache<T, C> {
    public static <T, C> BlockCapabilityCache<T, C> create(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context) { throw new UnsupportedOperationException(); }
    public static <T, C> BlockCapabilityCache<T, C> create(BlockCapability<T, C> capability, ServerLevel level, BlockPos pos, C context, BooleanSupplier isValid, Runnable invalidationListener) { throw new UnsupportedOperationException(); }
    public ServerLevel level() { throw new UnsupportedOperationException(); }
    public BlockPos pos() { throw new UnsupportedOperationException(); }
    public C context() { throw new UnsupportedOperationException(); }
    public T getCapability() { throw new UnsupportedOperationException(); }
}
