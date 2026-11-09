/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.forge.embedded;

import com.retromod.shim.common.embedded.LegacyLog;

/** Stands in for GameRegistry (removed in 1.13): its calls are accepted and register nothing. */
public class GameRegistryShim {

    public static void register(Object obj) {
        warnNotRegistered("register", obj == null ? null : obj.getClass());
    }

    public static void register(Object obj, Object name) {
        warnNotRegistered("register", obj == null ? null : obj.getClass());
    }

    public static void registerTileEntity(Class<?> clazz, Object name) {
        warnNotRegistered("registerTileEntity", clazz);
    }

    /** Warns once per method and class, because a mod registers many objects of one class. */
    private static void warnNotRegistered(String method, Class<?> type) {
        String typeName = type == null ? "null" : type.getName();
        LegacyLog.warnOnce("GameRegistry." + method + ":" + typeName, "An old mod called GameRegistry."
                + method + " for " + typeName + ". It has no equivalent on this loader, so nothing was "
                + "registered. Retromod has no replacement for this call yet. If the mod breaks, report "
                + "this line with the mod and its version.");
    }

    /** 1.12 world generators and fuel handlers have no modern hook; accept and ignore them. */
    public static void registerWorldGenerator(Object generator, int weight) {
    }

    public static void registerFuelHandler(Object handler) {
    }

    public static void addSmelting(Object input, Object output, float xp) {
        // Smelting recipes are data-driven in modern MC, so this is a no-op
    }

    public static void addShapedRecipe(Object output, Object... params) {
        // Recipes are data-driven in modern MC, so this is a no-op
    }

    public static void addShapelessRecipe(Object output, Object... params) {
        // Recipes are data-driven in modern MC, so this is a no-op
    }
}
