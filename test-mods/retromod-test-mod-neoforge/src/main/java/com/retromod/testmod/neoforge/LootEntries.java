/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * #336: 26.3 rebuilt the loot entry builders that mods use to add items to vanilla chests, so a
 * mod's loot handler failed on the old {@code LootPoolSingletonContainer.Builder}, and a nested
 * table became a holder instead of a key. Kept out of the mod class, so a type a host lacks fails
 * only its own check instead of the whole mod.
 */
final class LootEntries {

    private LootEntries() {}

    static boolean itemAndEmptyEntriesBuild() {
        LootPool.Builder pool = LootPool.lootPool()
                .add(LootItem.lootTableItem(Items.DIAMOND).setWeight(3)
                        .apply(SetItemCountFunction.setCount(ConstantValue.exactly(2.0F)))
                        .when(LootItemRandomChanceCondition.randomChance(0.5F)))
                .add(EmptyLootItem.emptyItem().setWeight(1))
                .when(LootItemRandomChanceCondition.randomChance(0.9F));
        return pool.build() != null;
    }

    /**
     * A loot handler that names another table by key, with the registries a loot event passes.
     * From 26.3 the key is looked up in those registries.
     */
    static Object nestedTable(HolderLookup.Provider registries) {
        return NestedLootTable.lootTableReference(ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath("minecraft", "chests/simple_dungeon")));
    }
}
