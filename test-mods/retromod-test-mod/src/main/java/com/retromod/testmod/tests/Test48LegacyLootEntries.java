/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.EmptyEntry;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.function.SetCountLootFunction;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;

/**
 * #336: 26.3 rebuilt the loot entry builders that mods use to add items to vanilla chests, and its
 * inventory takes a prediction when a menu hands items back as it closes. A mod's loot handler
 * then failed on the old builder class, and closing its menu failed on the old inventory call.
 *
 * <p>The inventory call on a null receiver proves linkage only: the bridge links, then fails on
 * the missing receiver.
 */
public final class Test48LegacyLootEntries implements Test {

    @Override
    public String description() {
        return "#336 loot entries built in code, and a menu handing items back";
    }

    @Override
    public TestResult run() {
        String failure = LootCalls.build() ? null : "a loot pool built from item and empty entries came back empty";
        if (failure == null) failure = linkage("PlayerInventory.offerOrDrop(stack)",
                () -> ((PlayerInventory) null).offerOrDrop(ItemStack.EMPTY));
        if (failure == null) failure = linkage("PlayerInventory.offer(stack, notifiesClient)",
                () -> ((PlayerInventory) null).offer(ItemStack.EMPTY, false));
        return failure == null ? TestResult.success() : TestResult.fail(failure);
    }

    /** Holds the calls, so a type a host lacks fails this test instead of loading the suite. */
    private static final class LootCalls {
        static boolean build() {
            LootPool.Builder pool = LootPool.builder()
                    .with(ItemEntry.builder(Items.DIAMOND).weight(3)
                            .apply(SetCountLootFunction.builder(ConstantLootNumberProvider.create(2.0F)))
                            .conditionally(RandomChanceLootCondition.builder(0.5F)))
                    .with(EmptyEntry.builder().weight(1))
                    .conditionally(RandomChanceLootCondition.builder(0.9F));
            return pool.build() != null;
        }
    }

    /** Only a linkage error counts: anything thrown after the call links is the null receiver. */
    private static String linkage(String label, Runnable probe) {
        try {
            probe.run();
            return null;
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            return label + " did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable linkedThenRefused) {
            return null;
        }
    }
}
