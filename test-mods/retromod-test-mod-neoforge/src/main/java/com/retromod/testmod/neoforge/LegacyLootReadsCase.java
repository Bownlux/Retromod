/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntries;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The loot reads Bookshelf and Moonlight 1.21.1 make, which 1.21.2, 26.1 and 26.3 renamed or removed: entry type
 * constants and getType(), loot context parameters, a nested table named by key with no registries in reach, and the
 * villager schedule registry.
 */
final class LegacyLootReadsCase {

    private static final String MOD_ID = "retromod_test_mod_neoforge";

    private LegacyLootReadsCase() {}

    static String run(MinecraftServer server) {
        if (LootItem.lootTableItem(Items.DIAMOND).build().getType() != LootPoolEntries.ITEM) {
            return "fail: a vanilla entry's type is not the ITEM constant";
        }
        LootParams params = new LootParams.Builder(server.overworld())
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO).create(LootContextParamSets.CHEST);
        LootContext context = new LootContext.Builder(params).create(Optional.empty());
        if (!Vec3.ZERO.equals(context.getParamOrNull(LootContextParams.ORIGIN)) || !Vec3.ZERO.equals(context.getParam(LootContextParams.ORIGIN))
                || !context.hasParam(LootContextParams.ORIGIN) || context.hasParam(LootContextParams.THIS_ENTITY)) {
            return "fail: the loot context parameters read wrong";
        }
        // getParam threw for a missing parameter, and old code relies on that instead of a null check.
        try {
            context.getParam(LootContextParams.THIS_ENTITY);
            return "fail: a missing loot context parameter did not throw";
        } catch (NoSuchElementException expected) {
            // The old behavior.
        }
        // The named table always rolls four emeralds.
        LootTable table = LootTable.lootTable().withPool(LootPool.lootPool().add(namedTable())).build();
        List<ItemStack> items = table.getRandomItems(params);
        if (items.size() != 1 || !items.get(0).is(Items.EMERALD) || items.get(0).getCount() != 4) {
            return "fail: the table named by key rolled " + items;
        }
        return BuiltInRegistries.SCHEDULE != null ? "success" : "fail: the schedule registry reads as null";
    }

    /** A nested table named by key in a method with no registries, as Moonlight's loot injection builds one. */
    private static LootPoolSingletonContainer.Builder<?> namedTable() {
        return NestedLootTable.lootTableReference(ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "legacy_singleton")));
    }
}
