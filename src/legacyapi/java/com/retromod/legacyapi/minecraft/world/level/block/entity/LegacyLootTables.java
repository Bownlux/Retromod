/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block.entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Answers the loot table id members of containers, which 1.20.5 turned into registry keys. */
public final class LegacyLootTables {

    // Reached by reflection, so one class serves ResourceLocation hosts and Identifier hosts alike.
    private static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";
    private static final String REGISTRIES = "net.minecraft.core.registries.Registries";
    private static final String CONTAINER = "net.minecraft.world.RandomizableContainer";

    /** The host's keyed members, read from its container interface so a mod's id-typed override never answers them. */
    private record HostMembers(Method getKey, Method setKey, Method setKeyAndSeed, Method setBlockEntity,
            Method createKey, Object lootRegistry, Method keyId) {}

    /** A container class's overrides of the old id-typed members; null where it declares none. */
    private record Overrides(Method getId, Method setId, Method setIdAndSeed) {}

    /** Each container class's overrides, looked up once, because Lootr reads and writes tables every tick. */
    private static final class OverrideCache extends ClassValue<Overrides> {
        @Override
        protected Overrides computeValue(Class<?> type) {
            return new Overrides(override(type, "getLootTable", 0), override(type, "setLootTable", 1),
                    override(type, "setLootTable", 2));
        }
    }

    private static final OverrideCache OVERRIDES = new OverrideCache();
    private static volatile HostMembers host;

    private LegacyLootTables() {}

    /** The old {@code lootTable} field read: the container's table as an id, or null when it has none. */
    public static Object lootTableId(Object container) {
        try {
            HostMembers members = host(container);
            Object key = members.getKey().invoke(container);
            return key == null ? null : members.keyId().invoke(key);
        } catch (ReflectiveOperationException e) {
            throw failure("read the loot table of", container, e);
        }
    }

    /** The old {@code lootTable} field write; null clears the table, as before. */
    public static void setLootTableId(Object container, Object id) {
        try {
            HostMembers members = host(container);
            members.setKey().invoke(container, keyOf(members, id));
        } catch (ReflectiveOperationException e) {
            throw failure("set the loot table of", container, e);
        }
    }

    /** The old {@code getLootTable()}, which runs a mod's own override of it as the old call did. */
    public static Object getLootTable(Object container) {
        Method override = OVERRIDES.get(container.getClass()).getId();
        return override != null ? call(override, container) : lootTableId(container);
    }

    /** The old {@code setLootTable(id)}, which runs a mod's own override of it as the old call did. */
    public static void setLootTable(Object container, Object id) {
        Method override = OVERRIDES.get(container.getClass()).setId();
        if (override != null) {
            call(override, container, id);
            return;
        }
        setLootTableId(container, id);
    }

    /** The old {@code setLootTable(id, seed)}, which runs a mod's own override of it as the old call did. */
    public static void setLootTable(Object container, Object id, long seed) {
        Method override = OVERRIDES.get(container.getClass()).setIdAndSeed();
        if (override != null) {
            call(override, container, id, seed);
            return;
        }
        hostSetLootTable(container, id, seed);
    }

    /** The same call from an override's own {@code super.setLootTable(id, seed)}: the host's form only. */
    public static void hostSetLootTable(Object container, Object id, long seed) {
        try {
            HostMembers members = host(container);
            members.setKeyAndSeed().invoke(container, keyOf(members, id), seed);
        } catch (ReflectiveOperationException e) {
            throw failure("set the loot table of", container, e);
        }
    }

    /** A loot table id read from text, in the host's id class: {@code Identifier} from 1.21.11, {@code ResourceLocation} before. */
    public static Object parseId(Object anchor, String text) {
        try {
            Class<?> idClass = host(anchor).createKey().getParameterTypes()[1];
            return idClass.getMethod("parse", String.class).invoke(null, text);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure("read the loot table id " + text + " for", anchor, e);
        } catch (ReflectiveOperationException e) {
            throw failure("read the loot table id " + text + " for", anchor, e);
        }
    }

    /** The old static {@code setLootTable(level, random, pos, id)}, now {@code setBlockEntityLootTable}. */
    public static void setBlockEntityLootTable(Object level, Object random, Object pos, Object id) {
        try {
            HostMembers members = host(level);
            if (members.setBlockEntity() == null) {
                throw new NoSuchMethodException("RandomizableContainer.setBlockEntityLootTable");
            }
            members.setBlockEntity().invoke(null, level, random, pos, keyOf(members, id));
        } catch (ReflectiveOperationException e) {
            throw failure("set the loot table at", pos, e);
        }
    }

    /** A declared override of an old id-typed member, opened for the call when its class is not public. */
    private static Method override(Class<?> type, String name, int parameters) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != parameters
                        || Modifier.isStatic(method.getModifiers()) || method.isBridge()) {
                    continue;
                }
                // The host's own members take or return a key; any other form is a translated mod's override.
                Class<?> typed = parameters == 0 ? method.getReturnType() : method.getParameterTypes()[0];
                if (typed.getName().equals(RESOURCE_KEY) || typed.isPrimitive()) continue;
                if (parameters == 2 && method.getParameterTypes()[1] != long.class) continue;
                if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isPublic(current.getModifiers())) {
                    method.setAccessible(true);
                }
                return method;
            }
        }
        return null;
    }

    private static Object call(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw failure("run the loot table override of", target, e);
        } catch (ReflectiveOperationException e) {
            throw failure("run the loot table override of", target, e);
        }
    }

    /** The loot table key for an id: {@code ResourceKey.create(Registries.LOOT_TABLE, id)}, or null for none. */
    private static Object keyOf(HostMembers members, Object id) throws ReflectiveOperationException {
        return id == null ? null : members.createKey().invoke(null, members.lootRegistry(), id);
    }

    /** The host's keyed members, found once from the class loader that holds the game. */
    private static HostMembers host(Object anchor) throws ReflectiveOperationException {
        HostMembers members = host;
        if (members != null) return members;
        ClassLoader loader = anchor.getClass().getClassLoader();
        Class<?> container = Class.forName(CONTAINER, false, loader);
        Class<?> resourceKey = Class.forName(RESOURCE_KEY, false, loader);
        Method createKey = null;
        for (Method method : resourceKey.getMethods()) {
            if (method.getName().equals("create") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == resourceKey) {
                createKey = method;
            }
        }
        Method setBlockEntity = null;
        for (Method method : container.getMethods()) {
            if (method.getName().equals("setBlockEntityLootTable") && method.getParameterCount() == 4) {
                setBlockEntity = method;
            }
        }
        if (createKey == null) throw new NoSuchMethodException("ResourceKey.create(registry, id)");
        // A key's id is identifier() from 26.1 and location() before.
        Method keyId;
        try {
            keyId = resourceKey.getMethod("identifier");
        } catch (NoSuchMethodException older) {
            keyId = resourceKey.getMethod("location");
        }
        members = new HostMembers(container.getMethod("getLootTable"), container.getMethod("setLootTable", resourceKey),
                container.getMethod("setLootTable", resourceKey, long.class), setBlockEntity, createKey,
                Class.forName(REGISTRIES, true, loader).getField("LOOT_TABLE").get(null), keyId);
        host = members;
        return members;
    }

    private static IllegalStateException failure(String action, Object target, Exception cause) {
        return new IllegalStateException("Retromod could not " + action + " " + target, cause);
    }
}
