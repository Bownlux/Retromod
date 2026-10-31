/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import com.retromod.legacyapi.neoforge.items.IItemHandler;
import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** An old item handler as a current resource handler, so current mods and vanilla machines can use it. */
public final class ItemHandlerResource extends SnapshotJournal<Integer> implements ResourceHandler<ItemResource> {

    private final IItemHandler handler;
    // Each change applied in an open transaction, with what undoes it: the slot's old stack for a
    // handler that can set slots, else the opposite insert or extract.
    private final List<Undo> undo = new ArrayList<>();

    private record Undo(int slot, ItemStack previous, ItemResource resource, int amount, boolean inserted) {}

    public ItemHandlerResource(IItemHandler handler) {
        this.handler = handler;
    }

    public IItemHandler handler() {
        return handler;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public ItemResource getResource(int index) {
        return ItemResource.of(handler.getStackInSlot(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return handler.getStackInSlot(index).getCount();
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        int limit = handler.getSlotLimit(index);
        return resource.isEmpty() ? limit : Math.min(limit, resource.getMaxStackSize());
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return !resource.isEmpty() && handler.isItemValid(index, resource.toStack(1));
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        if (amount <= 0 || resource.isEmpty()) return 0;
        updateSnapshots(transaction);
        ItemStack before = handler.getStackInSlot(index).copy();
        int inserted = amount - handler.insertItem(index, resource.toStack(amount), false).getCount();
        if (inserted > 0) undo.add(new Undo(index, before, resource, inserted, true));
        return inserted;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        if (amount <= 0 || resource.isEmpty() || !resource.matches(handler.getStackInSlot(index))) return 0;
        updateSnapshots(transaction);
        ItemStack before = handler.getStackInSlot(index).copy();
        int extracted = handler.extractItem(index, amount, false).getCount();
        if (extracted > 0) undo.add(new Undo(index, before, resource, extracted, false));
        return extracted;
    }

    @Override
    protected Integer createSnapshot() {
        return undo.size();
    }

    @Override
    protected void revertToSnapshot(Integer mark) {
        // Newest first, so each slot ends up as it was when the transaction began.
        for (int i = undo.size() - 1; i >= mark; i--) {
            Undo change = undo.remove(i);
            if (handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(change.slot(), change.previous());
            } else if (change.inserted()) {
                handler.extractItem(change.slot(), change.amount(), false);
            } else {
                handler.insertItem(change.slot(), change.resource().toStack(change.amount()), false);
            }
        }
    }

    @Override
    protected void onRootCommit(Integer originalState) {
        undo.clear();
    }
}
