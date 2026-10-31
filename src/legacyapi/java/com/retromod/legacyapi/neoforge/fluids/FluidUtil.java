/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids;

import com.retromod.legacyapi.neoforge.bridge.FluidHandlerResource;
import com.retromod.legacyapi.neoforge.bridge.Transactions;
import com.retromod.legacyapi.neoforge.capabilities.Capabilities;
import com.retromod.legacyapi.neoforge.capabilities.CapabilityCalls;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler.FluidAction;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandlerItem;
import com.retromod.legacyapi.neoforge.items.IItemHandler;
import com.retromod.legacyapi.neoforge.items.ItemHandlerHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** The old static fluid helpers: container fills and drains, transfers between handlers, and block interaction. */
public class FluidUtil {

    private FluidUtil() {}

    /** A player using their held container on a block's fluid handler; 26.3's own interaction does the work. */
    public static boolean interactWithFluidHandler(Player player, InteractionHand hand, Level level, BlockPos pos, Direction side) {
        try (Transaction transaction = Transactions.open()) {
            boolean interacted = net.neoforged.neoforge.transfer.fluid.FluidUtil.interactWithFluidHandler(player, hand, level, pos, side, transaction);
            if (interacted) transaction.commit();
            return interacted;
        }
    }

    /** A player using their held container on {@code handler}. */
    public static boolean interactWithFluidHandler(Player player, InteractionHand hand, IFluidHandler handler) {
        try (Transaction transaction = Transactions.open()) {
            boolean interacted = net.neoforged.neoforge.transfer.fluid.FluidUtil.interactWithFluidHandler(
                    player, hand, player.blockPosition(), new FluidHandlerResource(handler), transaction);
            if (interacted) transaction.commit();
            return interacted;
        }
    }

    /** Fills a copy of one {@code container} from {@code source}; the result holds the filled container. */
    public static FluidActionResult tryFillContainer(ItemStack container, IFluidHandler source, int maxAmount, Player player, boolean doFill) {
        Optional<IFluidHandlerItem> handler = getFluidHandler(container.copyWithCount(1));
        if (handler.isEmpty()) return FluidActionResult.FAILURE;
        IFluidHandlerItem filling = handler.get();
        FluidStack moved = tryFluidTransfer(filling, source, maxAmount, doFill);
        if (moved.isEmpty()) return FluidActionResult.FAILURE;
        // A simulated transfer leaves the copy as it was, so it is filled directly to show the result.
        if (!doFill) filling.fill(moved, FluidAction.EXECUTE);
        return new FluidActionResult(filling.getContainer());
    }

    /** Empties a copy of one {@code container} into {@code destination}; the result holds the emptied container. */
    public static FluidActionResult tryEmptyContainer(ItemStack container, IFluidHandler destination, int maxAmount, Player player, boolean doDrain) {
        Optional<IFluidHandlerItem> handler = getFluidHandler(container.copyWithCount(1));
        if (handler.isEmpty()) return FluidActionResult.FAILURE;
        IFluidHandlerItem draining = handler.get();
        FluidStack moved = tryFluidTransfer(destination, draining, maxAmount, doDrain);
        if (moved.isEmpty()) return FluidActionResult.FAILURE;
        if (!doDrain) draining.drain(moved, FluidAction.EXECUTE);
        return new FluidActionResult(draining.getContainer());
    }

    /** Fills one container from a stack of them and puts the filled one in {@code inventory}, or gives it to the player. */
    public static FluidActionResult tryFillContainerAndStow(ItemStack container, IFluidHandler source, IItemHandler inventory, int maxAmount, Player player, boolean doFill) {
        return stow(container, tryFillContainer(container, source, maxAmount, player, false), inventory, player, doFill,
                () -> tryFillContainer(container, source, maxAmount, player, true));
    }

    /** Empties one container from a stack of them and puts the empty one in {@code inventory}, or gives it to the player. */
    public static FluidActionResult tryEmptyContainerAndStow(ItemStack container, IFluidHandler destination, IItemHandler inventory, int maxAmount, Player player, boolean doDrain) {
        return stow(container, tryEmptyContainer(container, destination, maxAmount, player, false), inventory, player, doDrain,
                () -> tryEmptyContainer(container, destination, maxAmount, player, true));
    }

    private static FluidActionResult stow(ItemStack container, FluidActionResult simulated, IItemHandler inventory, Player player,
            boolean execute, java.util.function.Supplier<FluidActionResult> perform) {
        if (container.isEmpty() || !simulated.isSuccess()) return FluidActionResult.FAILURE;
        // A single container changes in place, with nothing left over to stow.
        if (container.getCount() == 1) return execute ? perform.get() : simulated;
        boolean fits = inventory != null && ItemHandlerHelper.insertItemStacked(inventory, simulated.getResult(), true).isEmpty();
        if (!fits && player == null) return FluidActionResult.FAILURE;
        if (execute) {
            ItemStack changed = perform.get().getResult();
            if (fits) ItemHandlerHelper.insertItemStacked(inventory, changed, false);
            else ItemHandlerHelper.giveItemToPlayer(player, changed);
        }
        ItemStack rest = container.copy();
        rest.shrink(1);
        return new FluidActionResult(rest);
    }

    /** Moves up to {@code maxAmount} of whatever {@code source} gives into {@code destination}. */
    public static FluidStack tryFluidTransfer(IFluidHandler destination, IFluidHandler source, int maxAmount, boolean doTransfer) {
        FluidStack available = source.drain(maxAmount, FluidAction.SIMULATE);
        return available.isEmpty() ? FluidStack.EMPTY : transfer(destination, source, available, doTransfer);
    }

    /** Moves up to {@code resource} from {@code source} into {@code destination}. */
    public static FluidStack tryFluidTransfer(IFluidHandler destination, IFluidHandler source, FluidStack resource, boolean doTransfer) {
        FluidStack available = source.drain(resource, FluidAction.SIMULATE);
        return available.isEmpty() || !FluidStack.isSameFluidSameComponents(available, resource)
                ? FluidStack.EMPTY : transfer(destination, source, available, doTransfer);
    }

    private static FluidStack transfer(IFluidHandler destination, IFluidHandler source, FluidStack available, boolean doTransfer) {
        int accepted = destination.fill(available, FluidAction.SIMULATE);
        if (accepted <= 0) return FluidStack.EMPTY;
        FluidStack portion = available.copyWithAmount(accepted);
        if (!doTransfer) return portion;
        FluidStack drained = source.drain(portion, FluidAction.EXECUTE);
        if (drained.isEmpty()) return FluidStack.EMPTY;
        int filled = destination.fill(drained, FluidAction.EXECUTE);
        return drained.copyWithAmount(filled);
    }

    /** The fluid handler of a single item, through the old item capability. */
    public static Optional<IFluidHandlerItem> getFluidHandler(ItemStack container) {
        return Optional.ofNullable((IFluidHandlerItem) CapabilityCalls.stackCapability(container, Capabilities.FluidHandler.ITEM));
    }

    /** The fluid a single container holds. */
    public static Optional<FluidStack> getFluidContained(ItemStack container) {
        if (container.isEmpty()) return Optional.empty();
        return getFluidHandler(container.copyWithCount(1))
                .map(handler -> handler.drain(Integer.MAX_VALUE, FluidAction.SIMULATE))
                .filter(stack -> !stack.isEmpty());
    }

    /** The fluid handler of a block, through the old block capability. */
    public static Optional<IFluidHandler> getFluidHandler(Level level, BlockPos pos, Direction side) {
        return Optional.ofNullable((IFluidHandler) CapabilityCalls.levelCapability(level, Capabilities.FluidHandler.BLOCK, pos, side));
    }

    /** A bucket of the fluid, or nothing when the fluid has no bucket. */
    public static ItemStack getFilledBucket(FluidStack stack) {
        return stack.isEmpty() ? ItemStack.EMPTY : new ItemStack(stack.getFluid().getBucket(), 1);
    }
}
