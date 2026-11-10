/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinLazyStaticInitializer;

/** Lets an old mod build item stacks before a world loads on hosts that bind item components only then. */
public final class LegacyEarlyItemStackBridge {

    public static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/item/EarlyItemStacks";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String DEFAULT_INSTANCE = "()L" + ITEM_STACK + ";";

    private static final String ITEM_LIKE = "Lnet/minecraft/world/level/ItemLike;";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String PATCH = "Lnet/minecraft/core/component/DataComponentPatch;";

    /** The public constructors 26.1 to 26.3 share with the versions old mods were built for. */
    static final String[] CONSTRUCTORS = {"(" + ITEM_LIKE + ")V", "(" + ITEM_LIKE + "I)V", "(" + HOLDER + ")V",
            "(" + HOLDER + "I)V", "(" + HOLDER + "I" + PATCH + ")V"};

    private LegacyEarlyItemStackBridge() {}

    /** Registers the bridge on a host whose item components are bound only when a world's data loads. */
    public static void register26x(RetromodTransformer t) {
        if (!MixinLazyStaticInitializer.hostBindsComponentsLate(RetromodVersion.TARGET_MC_VERSION, t.getFuzzyResolver())) {
            return;
        }
        registerRedirects(t);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, HELPER);
        // A mod built for 26.1 or newer already waits for a world, so its stacks stay the host's own.
        String since = MixinLazyStaticInitializer.FIRST_LATE_COMPONENTS_HOST;
        for (String constructor : CONSTRUCTORS) {
            String factory = constructor.substring(0, constructor.length() - 1) + "L" + ITEM_STACK + ";";
            t.registerConstructorRedirect(ITEM_STACK, constructor, HELPER, "of", factory);
            t.limitRedirectToModsBefore(ITEM_STACK, "<init>", constructor, since);
        }
        // A super call inside an item's own override keeps reaching Minecraft's, so the helper never calls itself.
        t.registerWrappingMethodRedirect(ITEM, "getDefaultInstance", DEFAULT_INSTANCE,
                HELPER, "defaultInstance", "(L" + ITEM + ";)L" + ITEM_STACK + ";");
        t.limitRedirectToModsBefore(ITEM, "getDefaultInstance", DEFAULT_INSTANCE, since);
    }
}
