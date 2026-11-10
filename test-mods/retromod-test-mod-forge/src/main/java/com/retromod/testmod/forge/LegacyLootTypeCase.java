/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Sophisticated Backpacks and Lootr register 1.20.1 loot function and condition types, records 26.1 deleted, whose
 * registries hold map codecs since. One goes through a deferred register and one through the register event, and a
 * loot table uses both. The world opens only if the table reads, so the results are in the log rather than the summary.
 */
final class LegacyLootTypeCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String MOD_ID = "retromod_test_mod_forge";
    private static final ResourceLocation TABLE = new ResourceLocation(MOD_ID, "legacy_loot_types");

    static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, MOD_ID);
    // Sophisticated Backpacks' function type: built around its Gson serializer in a deferred register's supplier.
    static final RegistryObject<LootItemFunctionType> SET_MARKED_COUNT =
            FUNCTIONS.register("legacy_set_count", () -> new LootItemFunctionType(new SetMarkedCount.Serializer()));
    // Lootr's condition type, registered through the event.
    static final LootItemConditionType ALWAYS = new LootItemConditionType(new AlwaysCondition.Serializer());

    private LegacyLootTypeCase() {}

    static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(LegacyLootTypeCase::registerInEvent);
        MinecraftForge.EVENT_BUS.addListener(LegacyLootTypeCase::check);
    }

    private static void registerInEvent(RegisterEvent event) {
        event.register(Registries.LOOT_CONDITION_TYPE, helper -> helper.register(new ResourceLocation(MOD_ID, "legacy_always"), ALWAYS));
    }

    private static void check(ServerStartedEvent event) {
        LootTable table = event.getServer().getLootData().getLootTable(TABLE);
        LOG.info("[Retromod-Test-Forge] A loot table with a 1.20.1 function type from a deferred register and a condition "
                + "type from the register event loads (server started): {}",
                table != LootTable.EMPTY ? "success" : "fail: the table is missing");
        LOG.info("[Retromod-Test-Forge] The loot table rolls with the old function and condition (server started): {}",
                roll(event.getServer().overworld(), table));
        String readBack;
        try {
            // The mod still reads its own entry as the type it registered.
            LootItemFunctionType type = SET_MARKED_COUNT.get();
            readBack = type != null ? "success" : "fail: the entry is empty";
        } catch (Throwable t) {
            readBack = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] The deferred register's loot function type reads back as its type (server started): {}",
                readBack);
    }

    private static String roll(ServerLevel level, LootTable table) {
        try {
            AlwaysCondition.TESTS.set(0);
            List<ItemStack> items = table.getRandomItems(new LootParams.Builder(level).create(LootContextParamSets.EMPTY));
            boolean counted = items.size() == 1 && items.get(0).getCount() == 3;
            // The pool's condition and the function's: 26.3 holds the function's as one condition, which must still be read.
            boolean bothTested = AlwaysCondition.TESTS.get() >= 2;
            return counted && bothTested ? "success"
                    : "fail: rolled " + items + ", conditions tested " + AlwaysCondition.TESTS.get() + " times";
        } catch (Throwable t) {
            return "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Sets the stack's count from its data, as a conditional function with an old Gson serializer. */
    static final class SetMarkedCount extends LootItemConditionalFunction {
        private final int count;

        SetMarkedCount(LootItemCondition[] conditions, int count) {
            super(conditions);
            this.count = count;
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            stack.setCount(count);
            return stack;
        }

        @Override
        public LootItemFunctionType getType() {
            return SET_MARKED_COUNT.get();
        }

        static final class Serializer extends LootItemConditionalFunction.Serializer<SetMarkedCount> {
            @Override
            public void serialize(JsonObject json, SetMarkedCount function, JsonSerializationContext context) {
                super.serialize(json, function, context);
                json.addProperty("count", function.count);
            }

            @Override
            public SetMarkedCount deserialize(JsonObject json, JsonDeserializationContext context, LootItemCondition[] conditions) {
                return new SetMarkedCount(conditions, json.get("count").getAsInt());
            }
        }
    }

    /** A condition that always passes, with an old Gson serializer. */
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

        static final class Serializer implements net.minecraft.world.level.storage.loot.Serializer<AlwaysCondition> {
            @Override
            public void serialize(JsonObject json, AlwaysCondition condition, JsonSerializationContext context) {
            }

            @Override
            public AlwaysCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                return new AlwaysCondition();
            }
        }
    }
}
