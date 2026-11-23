/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Empty stand-ins for built-in registries, and their keys, that the host removed or turned into data. */
public final class LegacyRemovedRegistrySupport {

    // One registry per removed name, so every read in this mod sees the same object.
    private static final Map<String, Object> REGISTRIES = new ConcurrentHashMap<>();
    // Registries old mods add entries to, such as Moonlight's villager schedule: these join the game's list of registries.
    private static final Set<String> REGISTRABLE = Set.of("schedule");

    private LegacyRemovedRegistrySupport() {}

    /** A removed {@code BuiltInRegistries} field: an empty, frozen registry under the old key {@code minecraft:<path>}. */
    public static Object registry(String path) {
        return REGISTRIES.computeIfAbsent(path, LegacyRemovedRegistrySupport::emptyRegistry);
    }

    private static Object emptyRegistry(String path) {
        try {
            ClassLoader loader = LegacyRemovedRegistrySupport.class.getClassLoader();
            if (REGISTRABLE.contains(path)) return registrable(loader, path);
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
            Class<?> lifecycle = Class.forName("com.mojang.serialization.Lifecycle", true, loader);
            Object registry = Class.forName("net.minecraft.core.MappedRegistry", true, loader)
                    .getConstructor(resourceKey, lifecycle)
                    .newInstance(keyOf(path), lifecycle.getMethod("stable").invoke(null));
            // Frozen like every built-in registry, so nothing can be added to a system the game removed.
            LegacyPublicMethods.method(registry.getClass(), "freeze").invoke(registry);
            return registry;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the empty minecraft:" + path
                    + " registry a mod built before its removal reads", e);
        }
    }

    /**
     * One registry for every mod's copy, open for entries: on NeoForge registered with the loader, elsewhere added to
     * the game's list of registries where the game still allows it, so code that finds registries by key finds it.
     */
    private static Object registrable(ClassLoader loader, String path) {
        Object registry = LegacyRegistries.registry("minecraft", path);
        // NeoForge registers it through NewRegistryEvent, which would then find the key taken.
        if (!onNeoForge(loader)) list(loader, path, registry);
        return registry;
    }

    private static boolean onNeoForge(ClassLoader loader) {
        try {
            Class.forName("net.neoforged.neoforge.registries.NewRegistryEvent", false, loader);
            return true;
        } catch (ClassNotFoundException notNeoForge) {
            return false;
        }
    }

    /** Adds the registry to the game's list; false where the list is closed or already has it. */
    private static boolean list(ClassLoader loader, String path, Object registry) {
        try {
            Class<?> registryType = Class.forName("net.minecraft.core.Registry", true, loader);
            Object root = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader).getField("REGISTRY").get(null);
            Class<?> identifier = identifierClass(loader);
            Object id = identifier.getMethod("withDefaultNamespace", String.class).invoke(null, path);
            registryType.getMethod("register", registryType, identifier, Object.class).invoke(null, root, id, registry);
            return true;
        } catch (ReflectiveOperationException | RuntimeException closed) {
            return false;
        }
    }

    /**
     * A removed {@code Registries} field: the old key {@code minecraft:<path>}. A registry old mods add entries to is
     * listed under it, where the game allows it; any other names no registry on this host.
     */
    public static Object registryKey(String path) {
        if (REGISTRABLE.contains(path)) registry(path);
        return keyOf(path);
    }

    private static Object keyOf(String path) {
        try {
            ClassLoader loader = LegacyRemovedRegistrySupport.class.getClassLoader();
            Class<?> identifier = identifierClass(loader);
            Object id = identifier.getMethod("withDefaultNamespace", String.class).invoke(null, path);
            return Class.forName("net.minecraft.resources.ResourceKey", true, loader)
                    .getMethod("createRegistryKey", identifier).invoke(null, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not name the removed minecraft:" + path + " registry", e);
        }
    }

    /** {@code Identifier}, which was {@code ResourceLocation} before 1.21.11. */
    private static Class<?> identifierClass(ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft.resources.Identifier", true, loader);
        } catch (ClassNotFoundException older) {
            return Class.forName("net.minecraft.resources.ResourceLocation", true, loader);
        }
    }
}
