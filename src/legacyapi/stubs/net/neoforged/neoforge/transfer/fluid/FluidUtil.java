// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.transfer.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public final class FluidUtil {
    public static boolean interactWithFluidHandler(Player player, InteractionHand hand, Level level, BlockPos pos, Direction side, TransactionContext transaction) { throw new UnsupportedOperationException(); }
    public static boolean interactWithFluidHandler(Player player, InteractionHand hand, BlockPos pos, ResourceHandler<FluidResource> handler, TransactionContext transaction) { throw new UnsupportedOperationException(); }
    public static FluidStack getFirstStackContained(ItemStack stack) { throw new UnsupportedOperationException(); }
}
