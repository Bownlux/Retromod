/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.retromod.testmod121.RetromodTestMod121;
import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.condition.LootCondition;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.loot.entry.LeafEntry;
import net.minecraft.loot.entry.LootPoolEntryType;
import net.minecraft.loot.function.LootFunction;
import net.minecraft.loot.function.SetCountLootFunction;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.Consumer;

/**
 * Moonlight and Bookshelf 1.21.1 extend {@code LootPoolSingletonContainer} (yarn's {@code LeafEntry}), which 26.3
 * removed, for their own loot entries. A data loot table names this one beside an entry of weight 0 and one whose
 * condition never passes, so every roll must pick it and apply its count function.
 */
public final class Test81OldSingletonLootEntry implements Test {

    static final LootPoolEntryType NAMED_ITEM = new LootPoolEntryType(NamedItemEntry.CODEC);

    private final MinecraftServer server;

    public Test81OldSingletonLootEntry(MinecraftServer server) {
        this.server = server;
    }

    /** Registers the entry type while the mod initializes, before the server reads its loot tables. */
    public static void registerType() {
        Registry.register(Registries.LOOT_POOL_ENTRY_TYPE, Identifier.of(RetromodTestMod121.MOD_ID, "legacy_named_item"), NAMED_ITEM);
    }

    @Override
    public String description() {
        return "a loot table naming a 1.21.1 entry on LootPoolSingletonContainer rolls it by weight with its condition and function";
    }

    @Override
    public TestResult run() {
        RegistryKey<LootTable> key = RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(RetromodTestMod121.MOD_ID, "legacy_singleton"));
        LootTable table = server.getReloadableRegistries().getLootTable(key);
        if (table == LootTable.EMPTY) return TestResult.fail("the loot table is missing");
        for (int roll = 0; roll < 20; roll++) {
            List<ItemStack> items = table.generateLoot(new LootContextParameterSet.Builder(server.getOverworld()).build(LootContextTypes.EMPTY));
            if (items.size() != 1 || !items.get(0).isOf(Items.EMERALD) || items.get(0).getCount() != 4) {
                return TestResult.fail("roll " + roll + " gave " + items);
            }
        }
        // The mod's own builder hands its constructor the lists it collected.
        if (!(NamedItemEntry.named(Items.DIAMOND).weight(3)
                .apply(SetCountLootFunction.builder(ConstantLootNumberProvider.create(2.0F)))
                .conditionally(RandomChanceLootCondition.builder(0.5F)).build() instanceof NamedItemEntry built)) {
            return TestResult.fail("the simple builder built something else");
        }
        if (built.weightValue() != 3 || built.conditionCount() != 1 || built.functionCount() != 1) {
            return TestResult.fail("the builder gave weight " + built.weightValue() + ", " + built.conditionCount()
                    + " conditions and " + built.functionCount() + " functions");
        }
        return TestResult.success();
    }

    /** An item named by id, as Moonlight's optional item entry is. */
    static final class NamedItemEntry extends LeafEntry {
        static final MapCodec<NamedItemEntry> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Registries.ITEM.getCodec().fieldOf("name").forGetter(entry -> entry.item))
                .and(addLeafFields(instance))
                .apply(instance, NamedItemEntry::new));

        private final Item item;

        NamedItemEntry(Item item, int weight, int quality, List<LootCondition> conditions, List<LootFunction> functions) {
            super(weight, quality, conditions, functions);
            this.item = item;
        }

        static LeafEntry.Builder<?> named(Item item) {
            return builder((weight, quality, conditions, functions) -> new NamedItemEntry(item, weight, quality, conditions, functions));
        }

        int weightValue() {
            return weight;
        }

        int conditionCount() {
            return conditions.size();
        }

        int functionCount() {
            return functions.size();
        }

        @Override
        protected void generateLoot(Consumer<ItemStack> output, LootContext context) {
            output.accept(new ItemStack(item));
        }

        @Override
        public LootPoolEntryType getType() {
            return NAMED_ITEM;
        }
    }
}
