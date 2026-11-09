/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Builds and adds old biome spawn entries on hosts that moved their weight to a weighted list. */
public final class LegacySpawns {
    private LegacySpawns() {}

    private static final String SPAWNER_DATA = "net.minecraft.world.level.biome.MobSpawnSettings$SpawnerData";

    // Every class here is reached by reflection, because this class is embedded into each mod and
    // compiled without Minecraft. Entries are records, so their weights are kept by identity. They
    // are never dropped: a mod may cache one entry and add it to every biome it matches.
    private static final Map<Object, Integer> WEIGHTS = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Old {@code new SpawnerData(type, weight, min, max)}, built in the host's shape. */
    public static Object spawnerData(Object entityType, int weight, int minCount, int maxCount) {
        try {
            ClassLoader loader = entityType.getClass().getClassLoader();
            Class<?> data = Class.forName(SPAWNER_DATA, true, loader);
            int max = Math.max(minCount, maxCount);
            Object entry = null;
            for (Constructor<?> constructor : data.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                // 1.21.5 to 26.2 keep the counts as (type, min, max).
                if (parameters.length == 3 && parameters[1] == int.class && parameters[2] == int.class) {
                    entry = constructor.newInstance(entityType, minCount, max);
                    break;
                }
                // 26.3 takes the counts as one IntProvider.
                if (parameters.length == 2) {
                    Object counts = Class.forName("net.minecraft.util.valueproviders.UniformInt", true, loader)
                            .getMethod("of", int.class, int.class).invoke(null, minCount, max);
                    entry = constructor.newInstance(entityType, counts);
                    break;
                }
            }
            if (entry == null) throw new NoSuchMethodException("SpawnerData(EntityType, counts)");
            WEIGHTS.put(entry, weight);
            return entry;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build a spawn entry for " + entityType, e);
        }
    }

    /** Old {@code builder.addSpawn(category, entry)}, with the weight the entry was built with. */
    public static Object addSpawn(Object builder, Object category, Object entry) {
        int weight = weightOf(entry);
        try {
            Method[] methods = LegacyPublicMethods.methods(builder.getClass());
            // 1.21.5 to 26.2: addSpawn(category, weight, entry).
            for (Method method : methods) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("addSpawn") && parameters.length == 3
                        && parameters[0].isInstance(category) && parameters[1] == int.class
                        && parameters[2].isInstance(entry)) {
                    return method.invoke(builder, category, weight, entry);
                }
            }
            // 26.3: addSpawn(type, category, weight, counts). Its (type, weight, counts) overload has
            // no category, so every parameter is matched.
            Object type = LegacyPublicMethods.method(entry.getClass(), "type").invoke(entry);
            Object counts = LegacyPublicMethods.method(entry.getClass(), "count").invoke(entry);
            for (Method method : methods) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("addSpawn") && parameters.length == 4
                        && parameters[0].isInstance(type) && parameters[1].isInstance(category)
                        && parameters[2] == int.class && parameters[3].isInstance(counts)) {
                    return method.invoke(builder, type, category, weight, counts);
                }
            }
            throw new NoSuchMethodException("MobSpawnSettings.Builder.addSpawn for " + category);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not add a spawn entry to a biome", e);
        }
    }

    /** Old structure override {@code addSpawn(entry)}, on hosts that only take an entry and a weight. */
    public static void addStructureSpawn(Object overrides, Object entry) {
        try {
            for (Method method : LegacyPublicMethods.methods(overrides.getClass())) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("addSpawn") && parameters.length == 2
                        && parameters[0].isInstance(entry) && parameters[1] == int.class) {
                    method.invoke(overrides, entry, weightOf(entry));
                    return;
                }
            }
            throw new NoSuchMethodException("StructureSpawnOverrideBuilder.addSpawn(entry, weight)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not add a spawn entry to a structure", e);
        }
    }

    /**
     * Old {@code MobSpawnSettingsBuilder.getSpawner(category)}, which returned a mutable list. The
     * host now returns a weighted builder, so this list adds and removes through it and reads a
     * fresh copy of its entries on every call.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> spawnerList(Object builder, Object category) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "add" -> {
                    // add(entry) and add(index, entry); a weighted list has no order to keep.
                    addSpawn(builder, category, args[args.length - 1]);
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                }
                case "addAll" -> {
                    Collection<?> added = (Collection<?>) args[args.length - 1];
                    for (Object entry : added) addSpawn(builder, category, entry);
                    return !added.isEmpty();
                }
                case "removeIf" -> {
                    return removeIf(builder, category, (Predicate<Object>) args[0]);
                }
                case "size", "isEmpty", "iterator", "listIterator", "stream", "parallelStream",
                        "spliterator", "forEach", "get", "contains", "containsAll", "indexOf",
                        "lastIndexOf", "toArray", "subList" -> {
                    return method.invoke(entries(builder, category), args);
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "toString" -> {
                    return "spawns of " + category + ": " + entries(builder, category);
                }
                default -> throw new UnsupportedOperationException("Retromod's spawn list for " + category
                        + " supports add and removeIf, not " + method.getName()
                        + ", because the host keeps biome spawns in a weighted list");
            }
        };
        return (List<Object>) java.lang.reflect.Proxy.newProxyInstance(List.class.getClassLoader(),
                new Class<?>[] {List.class}, handler);
    }

    private static int weightOf(Object entry) {
        Integer weight = WEIGHTS.get(entry);
        return weight == null ? 1 : weight;
    }

    /** The category's weighted builder, or null when the biome defines no spawns for it. */
    private static Object spawner(Object builder, Object category) throws ReflectiveOperationException {
        for (Method method : LegacyPublicMethods.methods(builder.getClass())) {
            if (method.getName().equals("getSpawner") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(category)
                    && !List.class.isAssignableFrom(method.getReturnType())) {
                return method.invoke(builder, category);
            }
        }
        throw new NoSuchMethodException("MobSpawnSettingsBuilder.getSpawner returning a weighted builder");
    }

    /** A read-only copy of the category's entries. */
    private static List<Object> entries(Object builder, Object category) throws ReflectiveOperationException {
        Object weighted = spawner(builder, category);
        if (weighted == null) return List.of();
        // NeoForge's weighted builder lists its entries; Forge's only builds them.
        List<?> listed;
        Method getList = noArgMethod(weighted, "getList");
        if (getList != null) {
            listed = (List<?>) getList.invoke(weighted);
        } else {
            Object built = LegacyPublicMethods.method(weighted.getClass(), "build").invoke(weighted);
            listed = (List<?>) LegacyPublicMethods.method(built.getClass(), "unwrap").invoke(built);
        }
        List<Object> values = new ArrayList<>(listed.size());
        for (Object weightedEntry : listed) values.add(valueOf(weightedEntry));
        return Collections.unmodifiableList(values);
    }

    /** Removes the matching entries from the category's weighted builder. */
    private static boolean removeIf(Object builder, Object category, Predicate<Object> filter)
            throws ReflectiveOperationException {
        Object weighted = spawner(builder, category);
        if (weighted == null) return false;
        int before = entries(builder, category).size();
        Method removeIf = LegacyPublicMethods.method(weighted.getClass(), "removeIf", Predicate.class);
        // NeoForge's removeIf tests each Weighted wrapper, while Forge's tests the entry itself.
        // getList, which only NeoForge has, tells them apart. Its list is read-only, so the
        // builder's own removeIf does the removing.
        Predicate<Object> test = noArgMethod(weighted, "getList") != null
                ? weightedEntry -> filter.test(valueOrFail(weightedEntry))
                : filter;
        removeIf.invoke(weighted, test);
        return entries(builder, category).size() != before;
    }

    private static Method noArgMethod(Object target, String name) {
        try {
            return LegacyPublicMethods.method(target.getClass(), name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object valueOf(Object weightedEntry) throws ReflectiveOperationException {
        return LegacyPublicMethods.method(weightedEntry.getClass(), "value").invoke(weightedEntry);
    }

    private static Object valueOrFail(Object weightedEntry) {
        try {
            return valueOf(weightedEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a weighted spawn entry", e);
        }
    }
}
