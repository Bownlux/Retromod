/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.RetromodTestMod121;
import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.entry.LootPoolEntryTypes;
import net.minecraft.loot.entry.LootTableEntry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The loot reads Bookshelf and Moonlight 1.21.1 make, which 1.21.2, 26.1 and 26.3 renamed or removed: entry type
 * constants and getType(), loot context parameters, a nested table named by key with no registries in reach, and the
 * villager schedule registry.
 */
public final class Test82OldLootReads implements Test {

    private final MinecraftServer server;

    public Test82OldLootReads(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "1.21.1 loot reads: entry type constants, context parameters, a table named by key and the schedule registry";
    }

    @Override
    public TestResult run() {
        if (ItemEntry.builder(Items.DIAMOND).build().getType() != LootPoolEntryTypes.ITEM) {
            return TestResult.fail("a vanilla entry's type is not the ITEM constant");
        }
        LootContextParameterSet parameters = new LootContextParameterSet.Builder(server.getOverworld())
                .add(LootContextParameters.ORIGIN, Vec3d.ZERO).build(LootContextTypes.CHEST);
        LootContext context = new LootContext.Builder(parameters).build(Optional.empty());
        if (!Vec3d.ZERO.equals(context.get(LootContextParameters.ORIGIN)) || !Vec3d.ZERO.equals(context.requireParameter(LootContextParameters.ORIGIN))
                || !context.hasParameter(LootContextParameters.ORIGIN) || context.hasParameter(LootContextParameters.THIS_ENTITY)) {
            return TestResult.fail("the loot context parameters read wrong");
        }
        // requireParameter threw for a missing parameter, and old code relies on that instead of a null check.
        try {
            context.requireParameter(LootContextParameters.THIS_ENTITY);
            return TestResult.fail("a missing loot context parameter did not throw");
        } catch (NoSuchElementException expected) {
            // The old behavior.
        }
        // Test81's table always rolls four emeralds.
        LootTable table = LootTable.builder().pool(LootPool.builder().with(namedTable())).build();
        List<ItemStack> items = table.generateLoot(parameters);
        if (items.size() != 1 || !items.get(0).isOf(Items.EMERALD) || items.get(0).getCount() != 4) {
            return TestResult.fail("the table named by key rolled " + items);
        }
        return Registries.SCHEDULE != null ? TestResult.success() : TestResult.fail("the schedule registry reads as null");
    }

    /** A nested table named by key in a method with no registries, as Moonlight's loot injection builds one. */
    private static net.minecraft.loot.entry.LeafEntry.Builder<?> namedTable() {
        return LootTableEntry.builder(RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(RetromodTestMod121.MOD_ID, "legacy_singleton")));
    }
}
