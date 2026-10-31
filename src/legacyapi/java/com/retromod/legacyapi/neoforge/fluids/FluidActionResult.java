/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids;

import net.minecraft.world.item.ItemStack;

/** Whether a container fill or drain worked, and the container it left. */
public class FluidActionResult {

    public static final FluidActionResult FAILURE = new FluidActionResult(false, ItemStack.EMPTY);

    public final boolean success;
    public final ItemStack result;

    public FluidActionResult(ItemStack result) {
        this(true, result);
    }

    private FluidActionResult(boolean success, ItemStack result) {
        this.success = success;
        this.result = result;
    }

    public boolean isSuccess() {
        return success;
    }

    public ItemStack getResult() {
        return result;
    }
}
