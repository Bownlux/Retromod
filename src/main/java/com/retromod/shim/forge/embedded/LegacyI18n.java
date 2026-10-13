/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.IllegalFormatException;

/**
 * Stands in for 1.12.2's client {@code I18n}, which mods also called from server code such as
 * command replies, where it does not exist. Translations come from the common {@code Language},
 * which every side has: the client's chosen language there, and the built-in English on a server.
 */
public final class LegacyI18n {
    private LegacyI18n() {}

    /** {@code I18n.format(key, args)}. */
    public static Object format(Object key, Object args) {
        String text = translate(String.valueOf(key));
        Object[] values = args instanceof Object[] array ? array : new Object[0];
        try {
            return values.length == 0 ? text : String.format(text, values);
        } catch (IllegalFormatException badPattern) {
            return text;
        }
    }
    public static Object func_135052_a(Object key, Object args) { return format(key, args); }
    /** The SRG table names {@code format} by its current name. */
    public static Object get(Object key, Object args) { return format(key, args); }

    /** {@code I18n.hasKey(key)}. */
    public static boolean hasKey(Object key) {
        return !translate(String.valueOf(key)).equals(String.valueOf(key));
    }
    public static boolean func_188566_a(Object key) { return hasKey(key); }
    public static boolean exists(Object key) { return hasKey(key); }

    private static String translate(String key) {
        try {
            Class<?> language = Class.forName("net.minecraft.locale.Language", true, LegacyI18n.class.getClassLoader());
            Object instance = null;
            for (Method method : language.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                        && method.getReturnType() == language) {
                    instance = method.invoke(null);
                    break;
                }
            }
            for (Method method : language.getMethods()) {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class
                        && method.getReturnType() == String.class) {
                    return (String) method.invoke(instance, key);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            // No language loaded yet; the key is the best answer.
        }
        return key;
    }
}
