/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Consumer;

/**
 * Moonlight and Bookshelf 1.21.1 extend {@code LootPoolSingletonContainer}, which 26.3 removed, for their own loot
 * entries. A data loot table names this one beside an entry of weight 0 and one whose condition never passes, so every
 * roll must pick it, test its condition and apply its count function.
 */
final class LegacySingletonEntryCase {

    private static final String MOD_ID = "retromod_test_mod_neoforge";

    static final DeferredRegister<LootPoolEntryType> ENTRIES = DeferredRegister.create(Registries.LOOT_POOL_ENTRY_TYPE, MOD_ID);
    static final DeferredHolder<LootPoolEntryType, LootPoolEntryType> NAMED_ITEM =
            ENTRIES.register("legacy_named_item", () -> new LootPoolEntryType(NamedItemEntry.CODEC));

    private LegacySingletonEntryCase() {}

    static void register(IEventBus modBus) {
        ENTRIES.register(modBus);
    }

    /** Rolls the table twenty times; the weight 0 entry never wins, and the old entry's condition and function run each time. */
    static String run(MinecraftServer server) {
        LootTable table = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id("legacy_singleton")));
        if (table == LootTable.EMPTY) return "fail: the loot table is missing";
        int tests = LegacyLootTypeCase.AlwaysCondition.TESTS.get();
        for (int roll = 0; roll < 20; roll++) {
            List<ItemStack> items = table.getRandomItems(new LootParams.Builder(server.overworld()).create(LootContextParamSets.EMPTY));
            if (items.size() != 1 || !items.get(0).is(Items.EMERALD) || items.get(0).getCount() != 4) {
                return "fail: roll " + roll + " gave " + items;
            }
        }
        if (LegacyLootTypeCase.AlwaysCondition.TESTS.get() - tests < 20) return "fail: the entry's condition was not tested";
        // The mod's own builder hands its constructor the lists it collected.
        if (!(NamedItemEntry.named(Items.DIAMOND).setWeight(3)
                .apply(SetItemCountFunction.setCount(ConstantValue.exactly(2.0F)))
                .when(LootItemRandomChanceCondition.randomChance(0.5F)).build() instanceof NamedItemEntry built)) {
            return "fail: the simple builder built something else";
        }
        if (built.weightValue() != 3 || built.conditionCount() != 1 || built.functionCount() != 1) {
            return "fail: the builder gave weight " + built.weightValue() + ", " + built.conditionCount()
                    + " conditions and " + built.functionCount() + " functions";
        }
        return NAMED_ITEM.get() != null ? "success" : "fail: the entry type reads back empty";
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** An item named by id, as Moonlight's optional item entry is. */
    static final class NamedItemEntry extends LootPoolSingletonContainer {
        static final MapCodec<NamedItemEntry> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("name").forGetter(entry -> entry.item))
                .and(singletonFields(instance))
                .apply(instance, NamedItemEntry::new));

        private final Item item;

        NamedItemEntry(Item item, int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions) {
            super(weight, quality, conditions, functions);
            this.item = item;
        }

        static LootPoolSingletonContainer.Builder<?> named(Item item) {
            return simpleBuilder((weight, quality, conditions, functions) -> new NamedItemEntry(item, weight, quality, conditions, functions));
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
        public void createItemStack(Consumer<ItemStack> output, LootContext context) {
            output.accept(new ItemStack(item));
        }

        @Override
        public LootPoolEntryType getType() {
            return NAMED_ITEM.get();
        }
    }
}
