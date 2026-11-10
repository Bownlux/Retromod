/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** The loot entry types code built before 26.1 reads, made from the map codecs 26.1's registry holds. */
public final class LegacyLootEntryTypeSupport {

    private static final String MAP_CODEC = "com.mojang.serialization.MapCodec";

    // One type per codec, so a type read from a removed constant and one read from an entry are the
    // same object, as they were when both named the registered type. Codecs live as long as the game.
    private static final Map<Object, Object> TYPES = Collections.synchronizedMap(new IdentityHashMap<>());

    private LegacyLootEntryTypeSupport() {}

    /** A removed {@code LootPoolEntries} constant: the registry's codec for {@code minecraft:<path>}, held as the old type. */
    public static Object entryType(Class<?> standIn, String path) {
        try {
            ClassLoader loader = standIn.getClassLoader();
            Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                    .getField("LOOT_POOL_ENTRY_TYPE").get(null);
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, loader);
            Object id = identifier.getMethod("withDefaultNamespace", String.class).invoke(null, path);
            Object codec = LegacyPublicMethods.method(registry.getClass(), "getValue", identifier).invoke(registry, id);
            if (codec == null) {
                throw new IllegalStateException("This Minecraft version has no loot entry type minecraft:" + path
                        + ", which a mod built before 26.1 reads from LootPoolEntries.");
            }
            return held(standIn, codec);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the loot entry type minecraft:" + path, e);
        }
    }

    /** {@code entry.getType()}: an entry built before 26.1 names its own type, any other gives its codec. */
    public static Object typeOf(Class<?> standIn, Object entry) {
        try {
            Object type = ownType(entry);
            if (type == null) type = LegacyPublicMethods.method(entry.getClass(), "codec").invoke(entry);
            return held(standIn, type);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read the type of the loot entry " + entry, e);
        }
    }

    /** The entry's own {@code getType()}, which returns its registered type, or null when it has none. */
    private static Object ownType(Object entry) throws ReflectiveOperationException {
        Class<?> mapCodec = Class.forName(MAP_CODEC, false, entry.getClass().getClassLoader());
        for (Method method : LegacyPublicMethods.methods(entry.getClass())) {
            if (method.getName().equals("getType") && method.getParameterCount() == 0
                    && mapCodec.isAssignableFrom(method.getReturnType())) {
                return method.invoke(entry);
            }
        }
        return null;
    }

    /** The codec as this mod's copy of the old type: itself when it already is one, else its one wrapper. */
    private static Object held(Class<?> standIn, Object codec) {
        if (standIn.isInstance(codec)) return codec;
        return TYPES.computeIfAbsent(codec, value -> {
            try {
                Class<?> mapCodec = Class.forName(MAP_CODEC, false, standIn.getClassLoader());
                return standIn.getConstructor(mapCodec).newInstance(value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not hold the loot entry codec " + value
                        + " as the type a mod built before 26.1 reads", e);
            }
        });
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException(cause);
    }
}
