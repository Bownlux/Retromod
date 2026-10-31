/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.bridge;

import com.retromod.legacyapi.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** A current item resource handler as an old item handler, for old mods that find one through a capability. */
public final class ResourceItemHandler implements IItemHandlerModifiable {

    private final ResourceHandler<ItemResource> handler;

    public ResourceItemHandler(ResourceHandler<ItemResource> handler) {
        this.handler = handler;
    }

    public ResourceHandler<ItemResource> handler() {
        return handler;
    }

    @Override
    public int getSlots() {
        return handler.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ItemResource resource = handler.getResource(slot);
        return resource.isEmpty() ? ItemStack.EMPTY : resource.toStack(handler.getAmountAsInt(slot));
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        int inserted;
        try (Transaction transaction = Transactions.open()) {
            inserted = handler.insert(slot, ItemResource.of(stack), stack.getCount(), transaction);
            if (!simulate) transaction.commit();
        }
        return inserted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - inserted);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        ItemResource resource = handler.getResource(slot);
        if (resource.isEmpty()) return ItemStack.EMPTY;
        int extracted;
        try (Transaction transaction = Transactions.open()) {
            extracted = handler.extract(slot, resource, amount, transaction);
            if (!simulate) transaction.commit();
        }
        return extracted <= 0 ? ItemStack.EMPTY : resource.toStack(extracted);
    }

    /** Empties the slot and fills it with {@code stack}, both or neither. */
    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        try (Transaction transaction = Transactions.open()) {
            ItemResource current = handler.getResource(slot);
            if (!current.isEmpty()) handler.extract(slot, current, handler.getAmountAsInt(slot), transaction);
            if (!stack.isEmpty()) handler.insert(slot, ItemResource.of(stack), stack.getCount(), transaction);
            transaction.commit();
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return handler.getCapacityAsInt(slot, ItemResource.EMPTY);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return handler.isValid(slot, ItemResource.of(stack));
    }
}
