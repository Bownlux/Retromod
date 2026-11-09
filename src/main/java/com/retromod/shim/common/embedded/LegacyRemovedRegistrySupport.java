/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Empty stand-ins for built-in registries, and their keys, that the host removed or turned into data. */
public final class LegacyRemovedRegistrySupport {

    // One registry per removed name, so every read in this mod sees the same object.
    private static final Map<String, Object> REGISTRIES = new ConcurrentHashMap<>();

    private LegacyRemovedRegistrySupport() {}

    /** A removed {@code BuiltInRegistries} field: an empty, frozen registry under the old key {@code minecraft:<path>}. */
    public static Object registry(String path) {
        return REGISTRIES.computeIfAbsent(path, LegacyRemovedRegistrySupport::emptyRegistry);
    }

    private static Object emptyRegistry(String path) {
        try {
            ClassLoader loader = LegacyRemovedRegistrySupport.class.getClassLoader();
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
            Class<?> lifecycle = Class.forName("com.mojang.serialization.Lifecycle", true, loader);
            Object registry = Class.forName("net.minecraft.core.MappedRegistry", true, loader)
                    .getConstructor(resourceKey, lifecycle)
                    .newInstance(registryKey(path), lifecycle.getMethod("stable").invoke(null));
            // Frozen like every built-in registry, so nothing can be added to a system the game removed.
            LegacyPublicMethods.method(registry.getClass(), "freeze").invoke(registry);
            return registry;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the empty minecraft:" + path
                    + " registry a mod built before its removal reads", e);
        }
    }

    /** A removed {@code Registries} field: the old key {@code minecraft:<path>}, which names no registry on this host. */
    public static Object registryKey(String path) {
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
