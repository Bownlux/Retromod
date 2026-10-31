/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import com.retromod.legacyapi.neoforge.bridge.ItemHandlerResource;
import com.retromod.legacyapi.neoforge.bridge.ResourceItemHandler;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** NeoForge's slot-based item handler, which 26.3 removed, as old mods call and implement it. */
public interface IItemHandler {

    /** The old handler for a current one; a current handler that wraps an old one gives that one back. */
    static IItemHandler of(ResourceHandler<ItemResource> handler) {
        if (handler instanceof ItemHandlerResource adapter) return adapter.handler();
        return new ResourceItemHandler(handler);
    }

    int getSlots();

    ItemStack getStackInSlot(int slot);

    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);

    ItemStack extractItem(int slot, int amount, boolean simulate);

    int getSlotLimit(int slot);

    boolean isItemValid(int slot, ItemStack stack);
}
