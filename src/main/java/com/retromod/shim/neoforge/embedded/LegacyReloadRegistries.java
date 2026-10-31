/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.embedded;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** The registry access a reload listener event used to give, which NeoForge 26.3 no longer passes to it. */
public final class LegacyReloadRegistries {

    private static volatile Object staticAccess;

    private LegacyReloadRegistries() {}

    /**
     * {@code event.getRegistryAccess()}. A mod keeps the result and reads it once the server runs,
     * so every call on it goes to the running server's registries; before there is a server, it
     * reads the built-in ones.
     */
    public static Object registryAccess(Object event) {
        try {
            ClassLoader loader = event.getClass().getClassLoader();
            Class<?> access = Class.forName("net.minecraft.core.RegistryAccess", false, loader);
            InvocationHandler forward = (proxy, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "Retromod registry access for an old reload listener";
                default -> invoke(method, current(loader), arguments);
            };
            return Proxy.newProxyInstance(access.getClassLoader(), new Class<?>[]{access}, forward);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Retromod could not stand in for the reload registry access", failure);
        }
    }

    private static Object current(ClassLoader loader) throws ReflectiveOperationException {
        Object server = Class.forName("net.neoforged.neoforge.server.ServerLifecycleHooks", false, loader)
                .getMethod("getCurrentServer").invoke(null);
        if (server != null) return server.getClass().getMethod("registryAccess").invoke(server);
        Object built = staticAccess;
        if (built == null) {
            Object root = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, loader).getField("REGISTRY").get(null);
            Class<?> registry = Class.forName("net.minecraft.core.Registry", false, loader);
            built = Class.forName("net.minecraft.core.RegistryAccess", false, loader)
                    .getMethod("fromRegistryOfRegistries", registry).invoke(null, root);
            staticAccess = built;
        }
        return built;
    }

    private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
