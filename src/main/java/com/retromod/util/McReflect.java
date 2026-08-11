/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-loader Minecraft class and member resolution.
 *
 * MC class names vary by loader: Yarn (Fabric dev), intermediary (Fabric prod),
 * Mojang (NeoForge/Forge). This tries multiple name variants and falls back to
 * Fabric's MappingResolver to translate yarn to intermediary in production.
 * Loader classes (net.fabricmc.*, net.neoforged.*) are unobfuscated and resolve
 * with plain Class.forName().
 */
public final class McReflect {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Reflect");

    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    // Fabric MappingResolver, lazily initialized, null off Fabric
    private static volatile Object mappingResolver;
    private static volatile Method mapClassNameMethod;
    private static volatile boolean resolverInitialized = false;

    // Offline override so the CLI can force the Forge -> NeoForge migration path (shim redirects,
    // toml promotion) without a live NeoForge runtime. Default false; only the CLI --target-loader
    // neoforge flag flips it, so nothing changes for a normal in-game run.
    private static volatile boolean forceNeoForge = false;

    private McReflect() {}

    /**
     * Force {@link #isNeoForge()} to report true regardless of the runtime. Used by the CLI's
     * {@code --target-loader neoforge} to drive the offline Forge -> NeoForge transform (there is
     * no live NeoForge on the CLI classpath). Off by default; never set on an in-game path.
     */
    public static void setForceNeoForge(boolean force) {
        forceNeoForge = force;
    }

    /** Whether the offline NeoForge-target override is active. */
    public static boolean isForceNeoForge() {
        return forceNeoForge;
    }

    /**
     * Find a Minecraft class by trying each name via Class.forName (dev + Mojang
     * names), then Fabric's MappingResolver (yarn to intermediary) on the first.
     *
     * @param names one or more possible class names (yarn first, then mojang, etc.)
     * @return the resolved Class, or null if not found
     */
    public static Class<?> findClass(String... names) {
        if (names == null || names.length == 0) return null;

        String cacheKey = names[0];
        Class<?> cached = CLASS_CACHE.get(cacheKey);
        if (cached != null) return cached;

        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            try {
                Class<?> c = loadClassWithoutInitialization(name);
                CLASS_CACHE.put(cacheKey, c);
                return c;
            } catch (ClassNotFoundException ignored) {
            }
        }

        Class<?> mapped = findClassViaMappingResolver(names[0]);
        if (mapped != null) {
            CLASS_CACHE.put(cacheKey, mapped);
            return mapped;
        }

        LOGGER.debug("Could not resolve MC class from names: {}", String.join(", ", names));
        return null;
    }

    /**
     * Find a Minecraft class, throwing if not found.
     */
    public static Class<?> requireClass(String... names) {
        Class<?> c = findClass(names);
        if (c == null) {
            throw new RuntimeException("Required MC class not found: " + String.join(", ", names));
        }
        return c;
    }

    /**
     * Find a method by trying multiple name variants.
     *
     * @param clazz      the class to search
     * @param paramTypes  the parameter types (use null for no-arg methods)
     * @param names       one or more possible method names
     * @return the Method, or null if not found
     */
    public static Method findMethod(Class<?> clazz, Class<?>[] paramTypes, String... names) {
        if (clazz == null) return null;

        for (String name : names) {
            if (name == null) continue;
            try {
                Method m;
                if (paramTypes != null) {
                    m = clazz.getMethod(name, paramTypes);
                } else {
                    m = clazz.getMethod(name);
                }
                return m;
            } catch (NoSuchMethodException ignored) {
                try {
                    Method m;
                    if (paramTypes != null) {
                        m = clazz.getDeclaredMethod(name, paramTypes);
                    } else {
                        m = clazz.getDeclaredMethod(name);
                    }
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored2) {
                }
            }
        }

        // last resort: match by name only, ignoring parameter types
        for (String name : names) {
            if (name == null) continue;
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
        }

        return null;
    }

    /**
     * Find a no-arg method by trying multiple name variants.
     */
    public static Method findMethod(Class<?> clazz, String... names) {
        return findMethod(clazz, null, names);
    }

    /**
     * Find a field by trying multiple name variants.
     *
     * @param clazz the class to search
     * @param names one or more possible field names
     * @return the Field, or null if not found
     */
    public static Field findField(Class<?> clazz, String... names) {
        if (clazz == null) return null;

        for (String name : names) {
            if (name == null) continue;
            try {
                return clazz.getField(name);
            } catch (NoSuchFieldException ignored) {
                try {
                    Field f = clazz.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored2) {
                }
            }
        }

        return null;
    }

    /**
     * Get an int field value, trying multiple field names.
     *
     * @param obj          the object instance
     * @param clazz        the class to look up the field on
     * @param defaultValue fallback if field not found
     * @param names        one or more possible field names
     * @return the field value, or defaultValue if not found
     */
    public static int getIntField(Object obj, Class<?> clazz, int defaultValue, String... names) {
        Field f = findField(clazz, names);
        if (f != null) {
            try {
                return f.getInt(obj);
            } catch (Exception e) {
                LOGGER.debug("Could not read int field: {}", e.getMessage());
            }
        }
        return defaultValue;
    }

    /**
     * Get an Object field value, trying multiple field names.
     */
    public static Object getField(Object obj, Class<?> clazz, String... names) {
        Field f = findField(clazz, names);
        if (f != null) {
            try {
                return f.get(obj);
            } catch (Exception e) {
                LOGGER.debug("Could not read field: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Check if a class exists on the classpath.
     */
    public static boolean classExists(String name) {
        try {
            loadClassWithoutInitialization(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Check if we're running on Fabric.
     */
    public static boolean isFabric() {
        return classExists("net.fabricmc.loader.api.FabricLoader");
    }

    /**
     * Check if we're running on NeoForge.
     */
    public static boolean isNeoForge() {
        return forceNeoForge || classExists("net.neoforged.neoforge.common.NeoForge");
    }

    /**
     * Check if we're running on Forge (legacy).
     */
    public static boolean isForge() {
        return classExists("net.minecraftforge.common.MinecraftForge");
    }

    /**
     * Resolve a yarn class name to the runtime name via Fabric's MappingResolver.
     * Returns null off Fabric or if mapping fails.
     */
    private static Class<?> findClassViaMappingResolver(String yarnName) {
        initResolver();
        if (mapClassNameMethod == null || mappingResolver == null) return null;

        try {
            String mapped = (String) mapClassNameMethod.invoke(mappingResolver, "named", yarnName);
            if (mapped != null && !mapped.equals(yarnName)) {
                return loadClassWithoutInitialization(mapped);
            }
        } catch (Exception e) {
            LOGGER.debug("MappingResolver lookup failed for {}: {}", yarnName, e.getMessage());
        }
        return null;
    }

    /**
     * Initialize the Fabric MappingResolver (once, thread-safe).
     */
    private static void initResolver() {
        if (resolverInitialized) return;
        synchronized (McReflect.class) {
            if (resolverInitialized) return;
            resolverInitialized = true;

            try {
                Class<?> loaderClass = loadClassWithoutInitialization(
                        "net.fabricmc.loader.api.FabricLoader");
                Object loader = loaderClass.getMethod("getInstance").invoke(null);
                mappingResolver = loaderClass.getMethod("getMappingResolver").invoke(loader);

                // Find the mapClassName method on the resolver
                for (Method m : mappingResolver.getClass().getMethods()) {
                    if ("mapClassName".equals(m.getName()) && m.getParameterCount() == 2) {
                        mapClassNameMethod = m;
                        break;
                    }
                }

                if (mapClassNameMethod != null) {
                    LOGGER.debug("Fabric MappingResolver initialized");
                }
            } catch (Exception e) {
                LOGGER.debug("Fabric MappingResolver not available (not on Fabric): {}", e.getMessage());
            }
        }
    }

    /**
     * Resolve a runtime class without running its static initializer.
     *
     * <p>These lookups run during loader startup. Initializing a Minecraft class here can execute
     * game code before the loader and mixins have finished setting up. Retromod's own loader is
     * preferred because it has the same view of Minecraft classes. The thread context loader is a
     * fallback for launchers that expose optional loader APIs there.</p>
     */
    private static Class<?> loadClassWithoutInitialization(String name) throws ClassNotFoundException {
        ClassLoader ownLoader = McReflect.class.getClassLoader();
        try {
            return Class.forName(name, false, ownLoader);
        } catch (ClassNotFoundException first) {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader == null || contextLoader == ownLoader) throw first;
            return Class.forName(name, false, contextLoader);
        }
    }
}
