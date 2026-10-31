/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler.FluidAction;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** An old fluid handler as a current resource handler. Changes apply at once and an aborted transaction reverses them. */
public final class FluidHandlerResource extends SnapshotJournal<Integer> implements ResourceHandler<FluidResource> {

    private final IFluidHandler handler;
    private final List<Change> changes = new ArrayList<>();

    private record Change(FluidResource resource, int amount, boolean filled) {}

    public FluidHandlerResource(IFluidHandler handler) {
        this.handler = handler;
    }

    public IFluidHandler handler() {
        return handler;
    }

    @Override
    public int size() {
        return handler.getTanks();
    }

    @Override
    public FluidResource getResource(int index) {
        return FluidResource.of(handler.getFluidInTank(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return handler.getFluidInTank(index).getAmount();
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        return handler.getTankCapacity(index);
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        return !resource.isEmpty() && handler.isFluidValid(index, resource.toStack(1));
    }

    // An old handler fills and drains as a whole, not tank by tank, so the index only reads.
    @Override
    public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
        if (amount <= 0 || resource.isEmpty()) return 0;
        updateSnapshots(transaction);
        int filled = handler.fill(resource.toStack(amount), FluidAction.EXECUTE);
        if (filled > 0) changes.add(new Change(resource, filled, true));
        return filled;
    }

    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        if (amount <= 0 || resource.isEmpty()) return 0;
        updateSnapshots(transaction);
        int drained = handler.drain(resource.toStack(amount), FluidAction.EXECUTE).getAmount();
        if (drained > 0) changes.add(new Change(resource, drained, false));
        return drained;
    }

    @Override
    protected Integer createSnapshot() {
        return changes.size();
    }

    @Override
    protected void revertToSnapshot(Integer mark) {
        for (int i = changes.size() - 1; i >= mark; i--) {
            Change change = changes.remove(i);
            FluidStack stack = change.resource().toStack(change.amount());
            if (change.filled()) handler.drain(stack, FluidAction.EXECUTE);
            else handler.fill(stack, FluidAction.EXECUTE);
        }
    }

    @Override
    protected void onRootCommit(Integer originalState) {
        changes.clear();
    }
}
