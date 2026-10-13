/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The old biome spawn entries for {@link com.retromod.shim.common.LegacyBiomeSpawnsBridge}.
 * {@code new SpawnerData(type, weight, min, max)} lost its weight to a weighted list, and
 * {@code addSpawn(category, data)} became {@code addSpawn(type, category, weight, counts)}. The
 * entry is built with its counts and its weight is kept until the mod adds it to a builder.
 * Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacySpawns {
    private LegacySpawns() {}

    /** Weights of entries not yet added to a builder; entries are records, so by identity. */
    private static final Map<Object, Integer> WEIGHTS = Collections.synchronizedMap(new IdentityHashMap<>());

    public static Object spawnerData(Object entityType, int weight, int minCount, int maxCount) {
        try {
            ClassLoader loader = entityType.getClass().getClassLoader();
            Object counts = Class.forName("net.minecraft.util.valueproviders.UniformInt", true, loader)
                    .getMethod("of", int.class, int.class).invoke(null, minCount, Math.max(minCount, maxCount));
            Class<?> data = Class.forName("net.minecraft.world.level.biome.MobSpawnSettings$SpawnerData", true, loader);
            for (Constructor<?> constructor : data.getConstructors()) {
                if (constructor.getParameterCount() == 2) {
                    Object entry = constructor.newInstance(entityType, counts);
                    WEIGHTS.put(entry, weight);
                    return entry;
                }
            }
            throw new NoSuchMethodException("SpawnerData(EntityType, IntProvider)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build a spawn entry for " + entityType, e);
        }
    }

    public static Object addSpawn(Object builder, Object category, Object entry) {
        Integer weight = WEIGHTS.remove(entry);
        try {
            Object type = entry.getClass().getMethod("type").invoke(entry);
            Object counts = entry.getClass().getMethod("count").invoke(entry);
            for (Method method : builder.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                // (type, category, weight, counts); (type, weight, min, max) also has an int third.
                if (method.getName().equals("addSpawn") && parameters.length == 4 && parameters[2] == int.class
                        && parameters[1].isInstance(category)) {
                    return method.invoke(builder, type, category, weight == null ? 1 : weight, counts);
                }
            }
            throw new NoSuchMethodException("MobSpawnSettings.Builder.addSpawn(type, category, weight, counts)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not add a spawn entry to a biome", e);
        }
    }
}
