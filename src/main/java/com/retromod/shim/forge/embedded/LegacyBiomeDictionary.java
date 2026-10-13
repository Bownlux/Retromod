/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * Stands in for 1.12.2's {@code BiomeDictionary}, which 1.19 replaced with biome tags.
 *
 * <p>A type is answered from the running server's biome tags: {@code HOT} is any biome tagged
 * {@code c:is_hot}, {@code forge:is_hot}, or {@code minecraft:is_hot}, including their
 * {@code /overworld} forms, and a few types read the tag that replaced them, such as
 * {@code minecraft:is_badlands} for {@code MESA}. Types a mod adds with {@code addTypes} are kept
 * too. Before a server is running there is no biome registry to read, so a lookup made while the
 * mod loads finds no biomes, and spawns a 1.12 mod adds to them then are not placed.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 * Calls on it are erased to {@code Object}.
 */
public final class LegacyBiomeDictionary {
    private LegacyBiomeDictionary() {}

    private static final Map<Object, Set<LegacyBiomeType>> ADDED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final String[] LIFECYCLE_HOOKS = {"net.minecraftforge.server.ServerLifecycleHooks",
            "net.neoforged.neoforge.server.ServerLifecycleHooks"};

    /** {@code addTypes(biome, types...)}. */
    public static void addTypes(Object biome, Object types) {
        if (biome == null || !(types instanceof Object[] array)) return;
        Set<LegacyBiomeType> added = ADDED.computeIfAbsent(biome, key -> new LinkedHashSet<>());
        for (Object type : array) {
            if (type instanceof LegacyBiomeType legacy) added.add(legacy);
        }
    }

    /** {@code hasType(biome, type)}. */
    public static boolean hasType(Object biome, Object type) {
        if (!(type instanceof LegacyBiomeType legacy) || biome == null) return false;
        Set<LegacyBiomeType> added = ADDED.get(biome);
        if (added != null && added.contains(legacy)) return true;
        return tagIds(biome).anyMatch(legacy::matchesTag);
    }

    /** {@code getTypes(biome)}: every type whose tags mark the biome, plus any it was given. */
    public static Object getTypes(Object biome) {
        Set<LegacyBiomeType> types = new LinkedHashSet<>();
        Set<LegacyBiomeType> added = ADDED.get(biome);
        if (added != null) types.addAll(added);
        java.util.List<String> tags = tagIds(biome).toList();
        for (Object type : (java.util.List<?>) LegacyBiomeType.getAll()) {
            LegacyBiomeType legacy = (LegacyBiomeType) type;
            if (tags.stream().anyMatch(legacy::matchesTag)) types.add(legacy);
        }
        return types;
    }

    /** {@code hasAnyType(biome)}. */
    public static boolean hasAnyType(Object biome) {
        return !((Set<?>) getTypes(biome)).isEmpty();
    }

    /** {@code areSimilar(first, second)}: the two biomes share a type. */
    public static boolean areSimilar(Object first, Object second) {
        Set<?> types = (Set<?>) getTypes(first);
        for (Object type : (Set<?>) getTypes(second)) {
            if (types.contains(type)) return true;
        }
        return false;
    }

    /** {@code makeBestGuess(biome)}: biome tags already classify every biome. */
    public static void makeBestGuess(Object biome) {
    }

    /** {@code getBiomes(type)}: the biomes of this type on the running server. */
    public static Object getBiomes(Object type) {
        Set<Object> biomes = new LinkedHashSet<>();
        if (!(type instanceof LegacyBiomeType)) return biomes;
        Object registry = biomeRegistry();
        if (registry instanceof Iterable<?> all) {
            for (Object biome : all) {
                if (hasType(biome, type)) biomes.add(biome);
            }
        }
        synchronized (ADDED) {
            ADDED.forEach((biome, types) -> {
                if (types.contains(type)) biomes.add(biome);
            });
        }
        return biomes;
    }

    // ---- registry access ----------------------------------------------------------------------

    /** The ids of the biome tags on this biome, such as {@code c:is_hot/overworld}. */
    private static Stream<String> tagIds(Object biome) {
        Object registry = biomeRegistry();
        if (registry == null) return Stream.empty();
        try {
            Object holder = call(registry, "wrapAsHolder", biome);
            Object tags = holder == null ? null : call(holder, "tags");
            if (!(tags instanceof Stream<?> stream)) return Stream.empty();
            return stream.map(tag -> {
                try {
                    return String.valueOf(call(tag, "location"));
                } catch (ReflectiveOperationException e) {
                    return "";
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Stream.empty();
        }
    }

    private static Object biomeRegistry() {
        Object server = currentServer();
        if (server == null) return null;
        try {
            Object access = call(server, "registryAccess");
            Object key = Class.forName("net.minecraft.core.registries.Registries", true,
                    server.getClass().getClassLoader()).getField("BIOME").get(null);
            for (String lookup : new String[] {"registryOrThrow", "lookupOrThrow"}) {
                try {
                    return call(access, lookup, key);
                } catch (NoSuchMethodException other) {
                    // the other version's name
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static Object currentServer() {
        for (String hooks : LIFECYCLE_HOOKS) {
            try {
                Class<?> lifecycle = Class.forName(hooks, true, LegacyBiomeDictionary.class.getClassLoader());
                return lifecycle.getMethod("getCurrentServer").invoke(null);
            } catch (ReflectiveOperationException | LinkageError absent) {
                // the other loader
            }
        }
        return null;
    }

    private static Object call(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
