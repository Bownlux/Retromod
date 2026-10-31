/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids.capability;

import net.minecraft.world.item.ItemStack;

/** A fluid handler on an item, whose container can change as it fills or drains, as a bucket does. */
public interface IFluidHandlerItem extends IFluidHandler {

    ItemStack getContainer();
}
