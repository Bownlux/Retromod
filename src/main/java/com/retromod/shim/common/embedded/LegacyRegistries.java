/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Registries a host removed, rebuilt once per game under Retromod's ids so every mod's copy of this class shares them. */
public final class LegacyRegistries {

    // Each mod embeds its own copy of this class, so the registries are kept where all copies see them.
    private static final String PROPERTY = "retromod.legacyRegistry.";
    private static final Set<String> LISTENING_BUSES = ConcurrentHashMap.newKeySet();

    private LegacyRegistries() {}

    /** The registry key {@code namespace:path}; a deferred register for it fills {@link #registry}. */
    public static Object key(String namespace, String path) {
        registry(namespace, path);
        try {
            ClassLoader loader = LegacyRegistries.class.getClassLoader();
            return keyClass(loader).getMethod("createRegistryKey", idClass(loader)).invoke(null, id(loader, namespace, path));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not name the registry " + namespace + ":" + path, failure);
        }
    }

    /** The registry itself, made on first use. */
    public static Object registry(String namespace, String path) {
        return System.getProperties().computeIfAbsent(PROPERTY + namespace + ":" + path, unused -> create(namespace, path));
    }

    private static Object create(String namespace, String path) {
        try {
            ClassLoader loader = LegacyRegistries.class.getClassLoader();
            Class<?> keyClass = keyClass(loader);
            Object key = keyClass.getMethod("createRegistryKey", idClass(loader)).invoke(null, id(loader, namespace, path));
            Class<?> builderClass;
            try {
                builderClass = Class.forName("net.neoforged.neoforge.registries.RegistryBuilder", false, loader);
            } catch (ClassNotFoundException notNeoForge) {
                // Elsewhere a mod registers straight into the registry object.
                Class<?> lifecycle = Class.forName("com.mojang.serialization.Lifecycle", false, loader);
                return Class.forName("net.minecraft.core.MappedRegistry", false, loader)
                        .getConstructor(keyClass, lifecycle).newInstance(key, lifecycle.getMethod("stable").invoke(null));
            }
            // NeoForge fills deferred registers only for registries it knows, so this one is
            // registered with it when NewRegistryEvent comes, after mod construction.
            Object builder = builderClass.getConstructor(keyClass).newInstance(key);
            builder = builderClass.getMethod("sync", boolean.class).invoke(builder, false);
            Object registry = builderClass.getMethod("create").invoke(builder);
            Class<?> newRegistryEvent = Class.forName("net.neoforged.neoforge.registries.NewRegistryEvent", false, loader);
            Method register = newRegistryEvent.getMethod("register", Class.forName("net.minecraft.core.Registry", false, loader));
            addModBusListener(newRegistryEvent, event -> {
                try {
                    register.invoke(event, registry);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Retromod could not register the registry " + namespace + ":" + path, failure);
                }
            });
            return registry;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not rebuild the registry " + namespace + ":" + path, failure);
        }
    }

    private static void addModBusListener(Class<?> event, Consumer<Object> listener) throws ReflectiveOperationException {
        ClassLoader loader = LegacyRegistries.class.getClassLoader();
        Object context = Class.forName("net.neoforged.fml.ModLoadingContext", false, loader).getMethod("get").invoke(null);
        Object container = context.getClass().getMethod("getActiveContainer").invoke(context);
        Object bus = container.getClass().getMethod("getEventBus").invoke(container);
        if (bus == null || !LISTENING_BUSES.add(System.identityHashCode(bus) + ":" + event.getName())) return;
        Class.forName("net.neoforged.bus.api.IEventBus", false, loader)
                .getMethod("addListener", Class.class, Consumer.class).invoke(bus, event, listener);
    }

    private static Class<?> keyClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("net.minecraft.resources.ResourceKey", false, loader);
    }

    private static Class<?> idClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("net.minecraft.resources.Identifier", false, loader);
    }

    private static Object id(ClassLoader loader, String namespace, String path) throws ReflectiveOperationException {
        return idClass(loader).getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
    }
}
