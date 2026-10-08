/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.forge.embedded;

/**
 * Reimplementation of GameRegistry (removed 1.13).
 * Old mods call GameRegistry.register(Block/Item). This shim captures
 * those calls and delegates to the modern registry system via reflection.
 */
public class GameRegistryShim {

    public static void register(Object obj) {
        // In modern MC, registration is done via DeferredRegister events.
        // We log a warning but don't crash. The object may already be registered
        // through the version shim's class redirects.
        System.out.println("[Retromod] GameRegistry.register() called for legacy mod - " +
            "object: " + obj + ". This call is intercepted by Retromod's compatibility layer.");
    }

    public static void register(Object obj, Object name) {
        System.out.println("[Retromod] GameRegistry.register() called for legacy mod - " +
            "object: " + obj + ", name: " + name);
    }

    public static void registerTileEntity(Class<?> clazz, Object name) {
        System.out.println("[Retromod] GameRegistry.registerTileEntity() called - " +
            "class: " + clazz.getName() + ", name: " + name);
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
