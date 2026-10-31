/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.items;

import net.minecraft.world.item.ItemStack;

/** An item handler whose slots can be set directly. */
public interface IItemHandlerModifiable extends IItemHandler {

    void setStackInSlot(int slot, ItemStack stack);
}
