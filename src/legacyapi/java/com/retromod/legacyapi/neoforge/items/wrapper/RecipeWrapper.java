/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items.wrapper;

import com.retromod.legacyapi.neoforge.items.IItemHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

/** An item handler read as a recipe input. */
public class RecipeWrapper implements RecipeInput {

    protected final IItemHandler inv;

    public RecipeWrapper(IItemHandler inv) {
        this.inv = inv;
    }

    @Override
    public int size() {
        return inv.getSlots();
    }

    @Override
    public ItemStack getItem(int slot) {
        return inv.getStackInSlot(slot);
    }
}
