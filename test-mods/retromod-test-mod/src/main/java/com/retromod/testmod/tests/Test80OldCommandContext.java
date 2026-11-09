/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;

/** A command context of the mod's own that keeps only diamonds, as Sophisticated Backpacks builds for its item argument. */
public final class Test80OldCommandContext implements Test {

    @Override
    public String description() {
        return "An old CommandRegistryAccess of the mod's own builds an item argument and keeps only its items";
    }

    @Override
    public TestResult run() {
        CommandRegistryAccess diamonds = new CommandRegistryAccess() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> RegistryWrapper<T> createWrapper(RegistryKey<? extends Registry<T>> key) {
                if (!key.equals(RegistryKeys.ITEM)) throw new IllegalStateException("No registry " + key);
                return (RegistryWrapper<T>) Registries.ITEM.getReadOnlyWrapper().filter(item -> item == Items.DIAMOND);
            }
        };
        // 26.x builds the item parser through the provider lookup, which a 1.20.1 context never had.
        ItemStackArgumentType.itemStack(diamonds);
        long items = diamonds.createWrapper(RegistryKeys.ITEM).streamEntries().count();
        return items == 1 ? TestResult.success() : TestResult.fail("the context kept " + items + " items");
    }
}
