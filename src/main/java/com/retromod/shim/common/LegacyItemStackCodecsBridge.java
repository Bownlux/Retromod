/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinLazyStaticInitializer;

/** Lets an old mod's item stack codecs read stacks before item components are bound, as 26.3 recipes load. */
public final class LegacyItemStackCodecsBridge {

    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/item/LegacyItemStackCodecs";
    private static final String CODEC = "()Lcom/mojang/serialization/Codec;";
    /** Each old field and the rebuilt getter that answers it. 1.21.1's strict and single item codecs are gone from 26.1. */
    static final String[][] FIELDS = {{"CODEC", "codec"}, {"STRICT_CODEC", "strict"}, {"OPTIONAL_CODEC", "optional"},
            {"SINGLE_ITEM_CODEC", "codec"}, {"STRICT_SINGLE_ITEM_CODEC", "strict"}, {"SIMPLE_ITEM_CODEC", "simple"}};

    static final String ITEM_COST = "net/minecraft/world/item/trading/ItemCost";
    static final String COSTS = RebuiltApiClasses.ROOT + "minecraft/world/item/LegacyItemCosts";

    private LegacyItemStackCodecsBridge() {}

    /** Registers on a host whose item components are bound only when a world's data loads. */
    public static void register26x(RetromodTransformer t) {
        if (!MixinLazyStaticInitializer.hostBindsComponentsLate(RetromodVersion.TARGET_MC_VERSION, t.getFuzzyResolver())) {
            return;
        }
        registerBridges(t);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerBridges(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, HELPER);
        // A mod built for 26.1 or newer keeps the host's codecs, as it keeps the host's stack constructors.
        String since = MixinLazyStaticInitializer.FIRST_LATE_COMPONENTS_HOST;
        for (String[] field : FIELDS) {
            t.registerStaticFieldBridge(ITEM_STACK, field[0], HELPER, field[1], CODEC, null, null);
            t.limitStaticFieldBridgeToModsBefore(ITEM_STACK, field[0], since);
        }
        // A trade cost builds its stack as it is read; Moonlight reads its trade files in a reload listener, before binding.
        RebuiltApiClasses.registerTree(t, COSTS);
        t.registerStaticFieldBridge(ITEM_COST, "CODEC", COSTS, "codec", CODEC, null, null);
        t.limitStaticFieldBridgeToModsBefore(ITEM_COST, "CODEC", since);
    }
}
