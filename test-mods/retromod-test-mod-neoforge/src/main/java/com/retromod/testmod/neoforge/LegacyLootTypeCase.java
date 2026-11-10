/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.List;

/**
 * Sophisticated Backpacks 1.21.1 registers its loot function type, a record 26.1 deleted, through a deferred register,
 * and Cobblemon its structure processor types through the register event; both registries hold map codecs since 26.1
 * and 26.2. A data loot table and processor list name them, so the world opens only if both registries read them.
 */
final class LegacyLootTypeCase {

    private static final String MOD_ID = "retromod_test_mod_neoforge";

    static final DeferredRegister<LootItemFunctionType<?>> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, MOD_ID);
    static final DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<SetMarkedCount>> SET_MARKED_COUNT =
            FUNCTIONS.register("legacy_set_count", () -> new LootItemFunctionType<>(SetMarkedCount.CODEC));
    static final LootItemConditionType ALWAYS = new LootItemConditionType(MapCodec.unit(AlwaysCondition::new));
    static final StructureProcessorType<KeepProcessor> KEEP = () -> KeepProcessor.CODEC;

    private LegacyLootTypeCase() {}

    static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(LegacyLootTypeCase::registerInEvent);
    }

    private static void registerInEvent(RegisterEvent event) {
        event.register(Registries.LOOT_CONDITION_TYPE, helper -> helper.register(id("legacy_always"), ALWAYS));
        event.register(Registries.STRUCTURE_PROCESSOR, helper -> helper.register(id("legacy_keep"), KEEP));
    }

    /**
     * The loot table that names both loot types rolls one diamond stack of three with the pool's and the function's
     * condition tested, the mod's holder reads back, and the function's simple builder builds it.
     */
    static String run(MinecraftServer server) {
        LootTable table = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id("legacy_loot_types")));
        if (table == LootTable.EMPTY) return "fail: the loot table is missing";
        AlwaysCondition.TESTS.set(0);
        List<ItemStack> items = table.getRandomItems(new LootParams.Builder(server.overworld()).create(LootContextParamSets.EMPTY));
        if (items.size() != 1 || items.get(0).getCount() != 3) return "fail: rolled " + items;
        if (AlwaysCondition.TESTS.get() < 2) return "fail: conditions tested " + AlwaysCondition.TESTS.get() + " times";
        if (!(SetMarkedCount.builder().build() instanceof SetMarkedCount)) return "fail: the simple builder built something else";
        return SET_MARKED_COUNT.get() != null ? "success" : "fail: the function type reads back empty";
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Sets the stack's count from its data. */
    static final class SetMarkedCount extends LootItemConditionalFunction {
        static final MapCodec<SetMarkedCount> CODEC = RecordCodecBuilder.mapCodec(instance -> commonFields(instance)
                .and(Codec.INT.fieldOf("count").forGetter(function -> function.count))
                .apply(instance, SetMarkedCount::new));
        private final int count;

        SetMarkedCount(List<LootItemCondition> conditions, int count) {
            super(conditions);
            this.count = count;
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            stack.setCount(count);
            return stack;
        }

        @Override
        public LootItemFunctionType<SetMarkedCount> getType() {
            return SET_MARKED_COUNT.get();
        }

        /** The builder a mod's own loot code uses: its constructor takes the condition list. */
        static LootItemConditionalFunction.Builder<?> builder() {
            return simpleBuilder(conditions -> new SetMarkedCount(conditions, 2));
        }
    }

    /** A condition that always passes. */
    static final class AlwaysCondition implements LootItemCondition {
        static final java.util.concurrent.atomic.AtomicInteger TESTS = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public LootItemConditionType getType() {
            return ALWAYS;
        }

        @Override
        public boolean test(LootContext context) {
            TESTS.incrementAndGet();
            return true;
        }
    }

    /** A processor that keeps every block. */
    static final class KeepProcessor extends StructureProcessor {
        static final MapCodec<KeepProcessor> CODEC = MapCodec.unit(KeepProcessor::new);

        @Override
        protected StructureProcessorType<?> getType() {
            return KEEP;
        }
    }
}
