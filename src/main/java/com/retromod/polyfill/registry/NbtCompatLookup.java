/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Answers the NBT getters, UUID helpers, and tag text and number reads that 1.21.5 removed, renamed, or made {@code Optional}. */
public final class NbtCompatLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-NbtCompat");

    /** Resolved methods cached after first lookup. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Method> METHOD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    // A Fabric 1.21.5 to 1.21.11 host runs intermediary names. Intermediary kept each member's id
    // when 1.21.5 changed its return or its Mojang name, so one alias covers the old and the new shape.
    private static final Map<String, String> INTERMEDIARY = Map.ofEntries(
            Map.entry("getString(String)", "method_10558"),
            Map.entry("getInt(String)", "method_10550"),
            Map.entry("getLong(String)", "method_10537"),
            Map.entry("getFloat(String)", "method_10583"),
            Map.entry("getDouble(String)", "method_10574"),
            Map.entry("getBoolean(String)", "method_10577"),
            Map.entry("getByte(String)", "method_10571"),
            Map.entry("getShort(String)", "method_10568"),
            Map.entry("getByteArray(String)", "method_10547"),
            Map.entry("getIntArray(String)", "method_10561"),
            Map.entry("getLongArray(String)", "method_10565"),
            Map.entry("get(String)", "method_10580"),
            Map.entry("putIntArray(String,int[])", "method_10539"),
            Map.entry("getString(int)", "method_10608"),
            Map.entry("getInt(int)", "method_10600"),
            Map.entry("asString()", "method_68658"),
            Map.entry("getAsString()", "method_10714"),
            Map.entry("getId()", "method_10711"),
            Map.entry("byteValue()", "method_10698"),
            Map.entry("shortValue()", "method_10696"),
            Map.entry("intValue()", "method_10701"),
            Map.entry("longValue()", "method_10699"),
            Map.entry("floatValue()", "method_10700"),
            Map.entry("doubleValue()", "method_10697"),
            Map.entry("box()", "method_10702"));

    private static final Class<?>[] NO_PARAMS = new Class<?>[0];

    private NbtCompatLookup() {}

    // CompoundTag getters. Each takes the receiver first, because the call is devirtualized to
    // INVOKESTATIC, and returns the old default for a missing key: empty string, zero, false.

    public static String compoundGetString(Object compound, String key) {
        Object v = unwrap(invokeGetter(compound, "getString", String.class, key));
        return v != null ? v.toString() : "";
    }

    public static int compoundGetInt(Object compound, String key) {
        Object v = unwrap(invokeGetter(compound, "getInt", int.class, key));
        return v instanceof Number n ? n.intValue() : 0;
    }

    public static long compoundGetLong(Object compound, String key) {
        Object v = unwrap(invokeGetter(compound, "getLong", long.class, key));
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public static float compoundGetFloat(Object compound, String key) {
        Object v = unwrap(invokeGetter(compound, "getFloat", float.class, key));
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    public static double compoundGetDouble(Object compound, String key) {
        Object v = unwrap(invokeGetter(compound, "getDouble", double.class, key));
        return v instanceof Number n ? n.doubleValue() : 0d;
    }

    public static boolean compoundGetBoolean(Object compound, String key) {
        Object v = unwrap(invokeGetter(compound, "getBoolean", boolean.class, key));
        return v instanceof Boolean b && b;
    }

    /** {@code getByte(key)}: any number tag narrowed to a byte, else 0. A null tag throws, as the old call did. */
    public static byte compoundGetByte(Object compound, String key) {
        Object v = unwrap(invokeGetter(Objects.requireNonNull(compound), "getByte", byte.class, key));
        return v instanceof Number n ? n.byteValue() : 0;
    }

    /** {@code getShort(key)}: any number tag narrowed to a short, else 0. */
    public static short compoundGetShort(Object compound, String key) {
        Object v = unwrap(invokeGetter(Objects.requireNonNull(compound), "getShort", short.class, key));
        return v instanceof Number n ? n.shortValue() : 0;
    }

    /** {@code getByteArray(key)}: the tag's own array, else a new empty one. */
    public static byte[] compoundGetByteArray(Object compound, String key) {
        Object v = unwrap(invokeGetter(Objects.requireNonNull(compound), "getByteArray", byte[].class, key));
        return v instanceof byte[] array ? array : new byte[0];
    }

    /** {@code getIntArray(key)}: the tag's own array, else a new empty one. */
    public static int[] compoundGetIntArray(Object compound, String key) {
        Object v = unwrap(invokeGetter(Objects.requireNonNull(compound), "getIntArray", int[].class, key));
        return v instanceof int[] array ? array : new int[0];
    }

    /** {@code getLongArray(key)}: the tag's own array, else a new empty one. */
    public static long[] compoundGetLongArray(Object compound, String key) {
        Object v = unwrap(invokeGetter(Objects.requireNonNull(compound), "getLongArray", long[].class, key));
        return v instanceof long[] array ? array : new long[0];
    }

    // UUIDs are stored as an int array of four, most significant int first, as 1.21.4 wrote them.

    /** {@code getUUID(key)}: throws for a missing key or a tag that is not four ints, as 1.21.4 did. */
    public static UUID compoundGetUUID(Object compound, String key) {
        Objects.requireNonNull(compound);
        // The old read called getType() on the missing tag, so a missing key was a NullPointerException.
        Object tag = call(compound, "get", null, new Class<?>[]{String.class}, key);
        if (tag == null) throw new NullPointerException("No UUID tag under key '" + key + "'");
        Object v = unwrap(invokeGetter(compound, "getIntArray", int[].class, key));
        if (!(v instanceof int[] ints)) {
            throw new IllegalArgumentException("Expected UUID-Tag to be of type INT[], but found " + tag + ".");
        }
        if (ints.length != 4) {
            throw new IllegalArgumentException("Expected UUID-Array to be of length 4, but found " + ints.length + ".");
        }
        return new UUID((long) ints[0] << 32 | ints[1] & 0xFFFFFFFFL, (long) ints[2] << 32 | ints[3] & 0xFFFFFFFFL);
    }

    /** {@code putUUID(key, uuid)}: writes the four-int array; a null UUID throws, as before. */
    public static void compoundPutUUID(Object compound, String key, UUID uuid) {
        Objects.requireNonNull(compound);
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        int[] ints = {(int) (most >>> 32), (int) most, (int) (least >>> 32), (int) least};
        Method put = resolveMethod(compound.getClass(), "putIntArray", void.class, new Class<?>[]{String.class, int[].class});
        if (put == null) throw new IllegalStateException("This host's " + compound.getClass().getName() + " has no putIntArray");
        invoke(put, compound, key, ints);
    }

    /** {@code hasUUID(key)}: true only for an int array of exactly four. */
    public static boolean compoundHasUUID(Object compound, String key) {
        Object v = unwrap(invokeGetter(Objects.requireNonNull(compound), "getIntArray", int[].class, key));
        return v instanceof int[] ints && ints.length == 4;
    }

    /** {@code getTagType(key)}: the type id of the tag under the key, or 0 when there is none. */
    public static byte compoundGetTagType(Object compound, String key) {
        Object tag = call(Objects.requireNonNull(compound), "get", null, new Class<?>[]{String.class}, key);
        return tag == null ? 0 : (Byte) callRequired(tag, "getId", byte.class);
    }

    // 1.21.5 made NumericTag an interface and renamed its getters, getAsInt to intValue and
    // getAsNumber to box. Each number tag still converts the way it did, such as flooring a float.

    public static byte numericGetAsByte(Object tag) {
        return (Byte) callRequired(Objects.requireNonNull(tag), "byteValue", byte.class);
    }

    public static short numericGetAsShort(Object tag) {
        return (Short) callRequired(Objects.requireNonNull(tag), "shortValue", short.class);
    }

    public static int numericGetAsInt(Object tag) {
        return (Integer) callRequired(Objects.requireNonNull(tag), "intValue", int.class);
    }

    public static long numericGetAsLong(Object tag) {
        return (Long) callRequired(Objects.requireNonNull(tag), "longValue", long.class);
    }

    public static float numericGetAsFloat(Object tag) {
        return (Float) callRequired(Objects.requireNonNull(tag), "floatValue", float.class);
    }

    public static double numericGetAsDouble(Object tag) {
        return (Double) callRequired(Objects.requireNonNull(tag), "doubleValue", double.class);
    }

    public static Number numericGetAsNumber(Object tag) {
        return (Number) callRequired(Objects.requireNonNull(tag), "box", Number.class);
    }

    /** {@code tag.getAsString()}: a string tag's own text; any other tag in its SNBT form. */
    public static String tagGetAsString(Object tag) {
        Objects.requireNonNull(tag);
        // 1.21.5 kept the raw text only on asString(), which is empty for every other tag type,
        // and the SNBT form on toString(), which getAsString() used to build.
        Method asString = resolveMethod(tag.getClass(), "asString", Optional.class, NO_PARAMS);
        if (asString != null) {
            return unwrap(invoke(asString, tag)) instanceof String text ? text : tag.toString();
        }
        Object legacy = call(tag, "getAsString", String.class, NO_PARAMS);
        return legacy instanceof String text ? text : tag.toString();
    }

    // ListTag getters, keyed by int index

    public static String listGetString(Object list, int index) {
        Object v = unwrap(invokeIndexedGetter(list, "getString", String.class, index));
        return v != null ? v.toString() : "";
    }

    public static int listGetInt(Object list, int index) {
        Object v = unwrap(invokeIndexedGetter(list, "getInt", int.class, index));
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** Unwrap an {@code Optional} result, or pass through a direct one. */
    private static Object unwrap(Object result) {
        if (result == null) return null;
        if (result instanceof Optional<?> opt) {
            return opt.isPresent() ? opt.get() : null;
        }
        return result;
    }

    /** Try the modern Optional-returning signature first, then the legacy direct one. */
    private static Object invokeGetter(Object receiver, String methodName,
                                       Class<?> legacyReturn, String key) {
        if (receiver == null) return null;
        Class<?> cls = receiver.getClass();

        Method modern = resolveCachedMethod(cls, methodName, String.class, Optional.class);
        if (modern != null) {
            try {
                return modern.invoke(receiver, key);
            } catch (Throwable t) {
                LOGGER.trace("modern {}.{} failed: {}", cls.getName(), methodName, t.getMessage());
            }
        }

        Method legacy = resolveCachedMethod(cls, methodName, String.class, legacyReturn);
        if (legacy != null) {
            try {
                return legacy.invoke(receiver, key);
            } catch (Throwable t) {
                LOGGER.trace("legacy {}.{} failed: {}", cls.getName(), methodName, t.getMessage());
            }
        }
        return null;
    }

    private static Object invokeIndexedGetter(Object receiver, String methodName,
                                              Class<?> legacyReturn, int index) {
        if (receiver == null) return null;
        Class<?> cls = receiver.getClass();

        Method modern = resolveCachedMethod(cls, methodName, int.class, Optional.class);
        if (modern != null) {
            try {
                return modern.invoke(receiver, index);
            } catch (Throwable t) {
                LOGGER.trace("modern {}.{}(int) failed: {}", cls.getName(), methodName, t.getMessage());
            }
        }

        Method legacy = resolveCachedMethod(cls, methodName, int.class, legacyReturn);
        if (legacy != null) {
            try {
                return legacy.invoke(receiver, index);
            } catch (Throwable t) {
                LOGGER.trace("legacy {}.{}(int) failed: {}", cls.getName(), methodName, t.getMessage());
            }
        }
        return null;
    }

    /** Calls a host method by its Mojang or intermediary name, or returns null when the host has neither. */
    private static Object call(Object receiver, String name, Class<?> returnType, Class<?>[] params, Object... args) {
        Method method = resolveMethod(receiver.getClass(), name, returnType, params);
        return method == null ? null : invoke(method, receiver, args);
    }

    /** Calls a no-argument method every 1.21.5 and newer host has, and names it if this host does not. */
    private static Object callRequired(Object receiver, String name, Class<?> returnType) {
        Method method = resolveMethod(receiver.getClass(), name, returnType, NO_PARAMS);
        if (method == null) {
            throw new IllegalStateException("This host's " + receiver.getClass().getName() + " has no " + name
                    + "(), so Retromod cannot answer the old NBT call that needs it");
        }
        return invoke(method, receiver);
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            // The host's own exception is the one the old call would have thrown.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + method + " on " + receiver.getClass().getName(), e);
        }
    }

    private static Method resolveCachedMethod(Class<?> cls, String name,
                                              Class<?> paramType, Class<?> returnType) {
        return resolveMethod(cls, name, returnType, new Class<?>[]{paramType});
    }

    /** Look up and cache a method under its Mojang name, then its intermediary one. */
    private static Method resolveMethod(Class<?> cls, String name, Class<?> returnType, Class<?>[] params) {
        String cacheKey = cls.getName() + "#" + name + Arrays.toString(params)
                + (returnType == null ? "*" : returnType.getName());
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Method found = findMethod(cls, name, returnType, params);
        String alias = INTERMEDIARY.get(name + "(" + simpleNames(params) + ")");
        if (found == null && alias != null) found = findMethod(cls, alias, returnType, params);
        if (found == null) return null;
        found.setAccessible(true);
        METHOD_CACHE.put(cacheKey, found);
        return found;
    }

    /** The first declared match in the class, its superclasses, and the interfaces they implement. */
    private static Method findMethod(Class<?> type, String name, Class<?> returnType, Class<?>[] params) {
        for (Class<?> walk = type; walk != null; walk = walk.getSuperclass()) {
            for (Method m : walk.getDeclaredMethods()) {
                // The return type picks between the Optional and direct overloads; null accepts any.
                if (name.equals(m.getName()) && Arrays.equals(m.getParameterTypes(), params)
                        && (returnType == null || m.getReturnType() == returnType)) {
                    return m;
                }
            }
            // Tag.asString() is a default method, so a tag type that keeps it is found on its interface.
            for (Class<?> iface : walk.getInterfaces()) {
                Method inherited = findMethod(iface, name, returnType, params);
                if (inherited != null) return inherited;
            }
        }
        return null;
    }

    private static String simpleNames(Class<?>[] params) {
        StringBuilder names = new StringBuilder();
        for (Class<?> param : params) {
            if (names.length() > 0) names.append(',');
            names.append(param.getSimpleName());
        }
        return names.toString();
    }
}
