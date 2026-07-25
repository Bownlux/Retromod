/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0: bridge the {@code KeyMapping} (keybind) constructor break in 26.x. Pre-26.x a mod wrote
 * {@code new KeyMapping(name, [type,] code, categoryString)} where the last argument was the
 * category as a {@code String} translation key ({@code "key.categories.misc"}, or a mod's own).
 * 26.x replaced that {@code String} with a {@code KeyMapping.Category} record (builtin constants
 * {@code MOVEMENT}/{@code MISC}/... plus {@code Category.register(Identifier)}), so the old
 * constructors are gone and any mod that adds a keybind dies {@code NoSuchMethodError} at client
 * init. A {@code new KeyMapping(...)} is rewritten (constructor-to-factory redirect) to one of these
 * factories, which resolves the category string to a {@code Category} and calls the real
 * constructor.
 *
 * <p><b>Category resolution:</b> a vanilla {@code key.categories.*} string maps to the matching
 * builtin constant; any other (mod) string registers a {@code Category} under a derived
 * {@code retromod:} identifier (cached, so repeated keybinds in the same category register once);
 * if registration isn't possible the keybind falls back to {@code MISC} so it still works, just
 * listed under Miscellaneous.
 *
 * <p><b>Zero compile-time Minecraft dependency</b> (Retromod builds without MC, and a per-mod
 * embedded copy must load with only what the mod's module sees): every MC type is reached
 * reflectively and results are typed {@code Object}; the redirect appends a {@code CHECKCAST}.
 *
 * <p><b>Fail-safe:</b> any reflection failure yields {@code null} (the redirect's {@code CHECKCAST
 * null} passes), i.e. that one keybind goes inert rather than crashing construction.
 */
public final class RetroKeyMapping {

    private RetroKeyMapping() {}

    private static final String KEY_MAPPING = "net.minecraft.client.KeyMapping";
    private static final String CATEGORY = "net.minecraft.client.KeyMapping$Category";
    private static final String INPUT_TYPE = "com.mojang.blaze3d.platform.InputConstants$Type";
    private static final String IDENTIFIER = "net.minecraft.resources.Identifier";

    // Vanilla category translation key -> the 26.x KeyMapping.Category builtin constant field name.
    private static final java.util.Map<String, String> VANILLA = java.util.Map.of(
            "key.categories.movement", "MOVEMENT",
            "key.categories.misc", "MISC",
            "key.categories.multiplayer", "MULTIPLAYER",
            "key.categories.gameplay", "GAMEPLAY",
            "key.categories.inventory", "INVENTORY",
            "key.categories.creative", "CREATIVE");

    // Categories we registered for mod strings, so a mod's repeated keybinds don't re-register.
    private static final java.util.Map<String, Object> REGISTERED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Old {@code new KeyMapping(String, InputConstants.Type, int, String)}. */
    public static Object create(String name, Object type, int code, String category) {
        try {
            Class<?> keyMapping = Class.forName(KEY_MAPPING);
            Class<?> categoryCls = Class.forName(CATEGORY);
            Class<?> inputType = Class.forName(INPUT_TYPE);
            Object cat = resolveCategory(categoryCls, category);
            if (cat == null) return null;
            return keyMapping.getConstructor(String.class, inputType, int.class, categoryCls)
                    .newInstance(name, type, code, cat);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Old {@code new KeyMapping(String, int, String)} (defaults the input type to KEYSYM). */
    public static Object createDefault(String name, int code, String category) {
        try {
            Class<?> keyMapping = Class.forName(KEY_MAPPING);
            Class<?> categoryCls = Class.forName(CATEGORY);
            Object cat = resolveCategory(categoryCls, category);
            if (cat == null) return null;
            return keyMapping.getConstructor(String.class, int.class, categoryCls)
                    .newInstance(name, code, cat);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object resolveCategory(Class<?> categoryCls, String category) {
        if (category != null) {
            String constant = VANILLA.get(category);
            if (constant != null) {
                Object c = staticField(categoryCls, constant);
                if (c != null) return c;
            }
            Object cached = REGISTERED.get(category);
            if (cached != null) return cached;
            Object registered = tryRegister(categoryCls, category);
            if (registered != null) {
                REGISTERED.put(category, registered);
                return registered;
            }
        }
        return staticField(categoryCls, "MISC"); // fail-safe: a MISC keybind still works
    }

    private static Object tryRegister(Class<?> categoryCls, String category) {
        try {
            Class<?> idCls = Class.forName(IDENTIFIER);
            Object id = idCls.getMethod("of", String.class, String.class)
                    .invoke(null, "retromod", sanitize(category));
            return categoryCls.getMethod("register", idCls).invoke(null, id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sanitize(String s) {
        String out = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._/-]", "_");
        return out.isEmpty() ? "custom" : out;
    }

    private static Object staticField(Class<?> cls, String field) {
        try {
            return cls.getField(field).get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
