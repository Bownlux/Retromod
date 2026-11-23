/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network.chat;

import net.minecraft.world.item.ItemStack;

/** The item a show-item hover event carried before 1.21.5, kept for old code that builds or reads one. */
public final class LegacyItemStackInfo {

    private final ItemStack stack;

    public LegacyItemStackInfo(ItemStack stack) {
        this.stack = stack;
    }

    /** The item shown. */
    public ItemStack getItemStack() {
        return stack;
    }
}
