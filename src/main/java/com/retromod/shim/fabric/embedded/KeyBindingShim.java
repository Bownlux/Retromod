/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * 1.21.9 changed the KeyBinding category ctor arg from String to a KeyBinding.Category record.
 */
package com.retromod.shim.fabric.embedded;

import com.retromod.util.McReflect;

import java.lang.reflect.*;
import java.util.*;

/**
 * Bridges the old String-based KeyBinding category constructor to the Category record added in 1.21.9.
 * McReflect resolves the class across yarn (dev) and intermediary (production) namespaces.
 */
public final class KeyBindingShim {

    private static final Map<String, Object> categoryCache = new HashMap<>();
    private static Class<?> categoryClass;
    private static Class<?> keyBindingClass;
    private static Constructor<?> newConstructor;
    private static Constructor<?> categoryConstructor;
    private static boolean initialized = false;
    private static boolean initFailed = false;

    private KeyBindingShim() {}

    /** Creates a KeyBinding from the old String category API. Called by transformed bytecode. */
    public static Object create(String translationKey, Object inputType, int keyCode, String categoryString) {
        // 26.3 renumbered keys for SDL; a no-op on a GLFW host.
        keyCode = com.retromod.polyfill.minecraft.RetroKeyMapping.hostCode(inputType, keyCode);
        initialize();

        if (initFailed) {
            return createFallback(translationKey, inputType, keyCode, categoryString);
        }

        try {
            Object category = getOrCreateCategory(categoryString);
            return newConstructor.newInstance(translationKey, inputType, keyCode, category);
        } catch (Exception e) {
            Object fallback = createFallback(translationKey, inputType, keyCode, categoryString);
            if (fallback != null) return fallback;
            throw new RuntimeException("Retromod: Failed to create KeyBinding for '" + translationKey + "'", e);
        }
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            keyBindingClass = McReflect.findClass(
                "net.minecraft.client.option.KeyBinding",  // yarn
                "net.minecraft.client.KeyMapping"          // mojang
            );

            if (keyBindingClass == null) {
                initFailed = true;
                return;
            }

            // On intermediary the simple name isn't "Category", so also accept a 1-component record.
            for (Class<?> inner : keyBindingClass.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("Category") || inner.isRecord()) {
                    if (inner.isRecord() && inner.getRecordComponents().length == 1) {
                        categoryClass = inner;
                        break;
                    }
                    if (inner.getSimpleName().equals("Category")) {
                        categoryClass = inner;
                        break;
                    }
                }
            }

            if (categoryClass == null) {
                // No Category record: this MC version still uses String categories.
                initFailed = true;
                return;
            }

            // Category ctor takes one Identifier/ResourceLocation arg.
            for (Constructor<?> c : categoryClass.getConstructors()) {
                if (c.getParameterCount() == 1) {
                    categoryConstructor = c;
                    break;
                }
            }
            if (categoryConstructor == null) {
                for (Constructor<?> c : categoryClass.getDeclaredConstructors()) {
                    if (c.getParameterCount() == 1) {
                        c.setAccessible(true);
                        categoryConstructor = c;
                        break;
                    }
                }
            }

            for (Constructor<?> c : keyBindingClass.getConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 4 && params[3] == categoryClass) {
                    newConstructor = c;
                    break;
                }
            }
            if (newConstructor == null) {
                for (Constructor<?> c : keyBindingClass.getDeclaredConstructors()) {
                    Class<?>[] params = c.getParameterTypes();
                    if (params.length == 4 && params[3] == categoryClass) {
                        c.setAccessible(true);
                        newConstructor = c;
                        break;
                    }
                }
            }

            if (newConstructor == null || categoryConstructor == null) {
                initFailed = true;
            }

        } catch (Exception e) {
            initFailed = true;
            System.err.println("Retromod: KeyBindingShim init warning: " + e.getMessage());
        }
    }

    // Cached: re-registering the same category throws.
    private static Object getOrCreateCategory(String categoryString) throws Exception {
        if (categoryCache.containsKey(categoryString)) {
            return categoryCache.get(categoryString);
        }

        String namespace;
        String path;

        if (categoryString.contains(":")) {
            String[] parts = categoryString.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        } else if (categoryString.startsWith("key.categories.")) {
            String rest = categoryString.substring("key.categories.".length());
            namespace = "minecraft";
            path = rest;
        } else {
            namespace = "retromod_compat";
            path = categoryString.replace('.', '_').replace(' ', '_').toLowerCase();
        }

        Object identifier = IdentifierShim.of(namespace, path);
        Object category = categoryConstructor.newInstance(identifier);
        categoryCache.put(categoryString, category);
        return category;
    }

    // Pre-1.21.9 hosts still have the String-category constructor.
    private static Object createFallback(String translationKey, Object inputType, int keyCode, String categoryString) {
        if (keyBindingClass == null) {
            keyBindingClass = McReflect.findClass(
                "net.minecraft.client.option.KeyBinding",
                "net.minecraft.client.KeyMapping"
            );
        }
        if (keyBindingClass == null) return null;

        for (Constructor<?> c : keyBindingClass.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 4 && params[3] == String.class) {
                try {
                    return c.newInstance(translationKey, inputType, keyCode, categoryString);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        for (Constructor<?> c : keyBindingClass.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 4 && params[3] == String.class) {
                try {
                    c.setAccessible(true);
                    return c.newInstance(translationKey, inputType, keyCode, categoryString);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
