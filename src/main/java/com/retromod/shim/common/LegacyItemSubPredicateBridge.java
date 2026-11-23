/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Keeps an item sub-predicate written before 1.21.5 registering and matching on a host whose predicate type is an interface. */
public final class LegacyItemSubPredicateBridge {

    static final String OLD = "net/minecraft/advancements/critereon/ItemSubPredicate";
    static final String OLD_TYPE = OLD + "$Type";
    static final String PREDICATE = "net/minecraft/core/component/predicates/DataComponentPredicate";
    static final String TYPE = PREDICATE + "$Type";
    static final String CONCRETE_TYPE = PREDICATE + "$ConcreteType";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/advancements/LegacyItemSubPredicate";
    static final String CODEC_CTOR = "(Lcom/mojang/serialization/Codec;)V";
    /** The first release checked to have the interface type and its concrete class; 1.21.5 to 1.21.10 were not. */
    static final String FIRST_CHECKED = "1.21.11";
    /** The release that renamed ItemSubPredicate to DataComponentPredicate with matches(DataComponentGetter). */
    static final String FIRST_PREDICATE_VERSION = "1.21.5";

    private LegacyItemSubPredicateBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostHasConcreteType()) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        // The old nested record keeps the name its outer class moved to; the outer move is in the rename table.
        t.registerClassRedirect(OLD_TYPE, TYPE);
        // new Type(codec) on an interface cannot link, so it builds the host's concrete type, whichever name it carries.
        for (String type : new String[] {OLD_TYPE, TYPE}) {
            t.registerConstructorRedirect(type, CODEC_CTOR, HELPER, "type",
                    "(Lcom/mojang/serialization/Codec;)L" + TYPE + ";", true);
        }
        // A mod's predicate keeps its matches(ItemStack); the rebuilt interface answers the host's matches(DataComponentGetter).
        for (String predicate : new String[] {OLD, PREDICATE}) t.registerInterfaceReplacement(predicate, HELPER);
        // A mod built for 1.21.5 or newer implements the current interface itself, so it keeps it.
        t.limitInterfaceReplacementToModsBefore(PREDICATE, FIRST_PREDICATE_VERSION);
        // The registry kept its role under a new name.
        t.registerFieldRedirect("net/minecraft/core/registries/Registries", "ITEM_SUB_PREDICATE_TYPE",
                "net/minecraft/core/registries/Registries", "DATA_COMPONENT_PREDICATE_TYPE");
        t.registerFieldRedirect("net/minecraft/core/registries/BuiltInRegistries", "ITEM_SUB_PREDICATE_TYPE",
                "net/minecraft/core/registries/BuiltInRegistries", "DATA_COMPONENT_PREDICATE_TYPE");
    }

    /** Whether the host has the concrete type; offline, whether the target is at least the first release checked. */
    static boolean hostHasConcreteType() {
        if (ClassResourceInspector.read(PREDICATE) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_CHECKED) >= 0;
        }
        return ClassResourceInspector.read(CONCRETE_TYPE) != null;
    }
}
