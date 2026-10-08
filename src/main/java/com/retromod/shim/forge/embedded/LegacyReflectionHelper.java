/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Stand-in for the 1.12.2 {@code ReflectionHelper} and {@code ObfuscationReflectionHelper}. Both
 * looked a private member up by a list of candidate names, usually the MCP name and the SRG id.
 *
 * <p>Those names belong to 1.12, so a lookup on a Minecraft class rarely matches on a modern
 * host. A 1.12 helper threw in that case. Here {@code findField} and {@code findMethod} return
 * null instead, because mods cache the result in a static initializer and a throw there fails
 * the whole item or block class. Reading or writing through a member that does not exist still
 * throws, so the failure surfaces where the member is used. Lookups on the mod's own classes
 * work as before.
 */
public final class LegacyReflectionHelper {

    private LegacyReflectionHelper() {}

    /*
     * The single-name overloads are separate methods, not the varargs ones: a call compiled
     * against ObfuscationReflectionHelper's one-name forms (AbyssalCraft uses findField and
     * setPrivateValue that way) links by its exact descriptor, which has no String[].
     */

    /** {@code ObfuscationReflectionHelper.findField(Class, String)}. */
    public static Field findField(Class<?> owner, String name) {
        return findField(owner, new String[]{name});
    }

    /** {@code ReflectionHelper.findField(Class, String, String)}: the MCP name, then the SRG id. */
    public static Field findField(Class<?> owner, String name, String obfName) {
        return findField(owner, new String[]{name, obfName});
    }

    public static Field findField(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                Field f = owner.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // Try the next candidate name.
            }
        }
        LegacyForgeEvents.note("reflective lookups of 1.12 member names", owner);
        return null;
    }

    public static Method findMethod(Class<?> owner, String name, String obfName, Class<?>... params) {
        for (String candidate : new String[]{name, obfName}) {
            if (candidate == null) continue;
            try {
                Method m = owner.getDeclaredMethod(candidate, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException | RuntimeException ignored) {
                // Try the next candidate name.
            }
        }
        LegacyForgeEvents.note("reflective lookups of 1.12 member names", owner);
        return null;
    }

    /** {@code ObfuscationReflectionHelper.findMethod(Class, String, Class, Class...)}. */
    public static Method findMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... params) {
        return findMethod(owner, name, (String) null, params);
    }

    /** {@code ObfuscationReflectionHelper.getPrivateValue(Class, Object, String)}. */
    public static Object getPrivateValue(Class<?> owner, Object instance, String name) {
        return getPrivateValue(owner, instance, new String[]{name});
    }

    public static Object getPrivateValue(Class<?> owner, Object instance, String... names) {
        try {
            return requireField(owner, names).get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + owner.getName() + "." + names[0], e);
        }
    }

    public static Object getPrivateValue(Class<?> owner, Object instance, int index) {
        try {
            Field f = owner.getDeclaredFields()[index];
            f.setAccessible(true);
            return f.get(instance);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalStateException("Cannot read field " + index + " of " + owner.getName(), e);
        }
    }

    /** {@code ObfuscationReflectionHelper.setPrivateValue(Class, Object, Object, String)}. */
    public static void setPrivateValue(Class<?> owner, Object instance, Object value, String name) {
        setPrivateValue(owner, instance, value, new String[]{name});
    }

    public static void setPrivateValue(Class<?> owner, Object instance, Object value, String... names) {
        try {
            requireField(owner, names).set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write " + owner.getName() + "." + names[0], e);
        }
    }

    public static void setPrivateValue(Class<?> owner, Object instance, Object value, int index) {
        try {
            Field f = owner.getDeclaredFields()[index];
            f.setAccessible(true);
            f.set(instance, value);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalStateException("Cannot write field " + index + " of " + owner.getName(), e);
        }
    }

    private static Field requireField(Class<?> owner, String... names) {
        Field f = findField(owner, names);
        if (f == null) {
            throw new IllegalStateException("None of " + java.util.Arrays.toString(names)
                    + " exists on " + owner.getName() + " in this Minecraft version");
        }
        return f;
    }
}
