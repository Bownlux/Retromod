/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * Lets a pre-1.19.3 mod's direct {@code IForgeRegistry.register} call land after that registry's
 * own event.
 *
 * <p>MCreator mods of the 1.19.2 era register some types, such as a tree decorator type, from a
 * static initializer: {@code ForgeRegistries.TREE_DECORATOR_TYPES.register(name, type)}. That
 * initializer first runs when the mod builds a biome, which on 1.20.1 Forge is after the tree
 * decorator registry's {@code RegisterEvent} has finished. Forge then locks each registry once its
 * event ends, so the call throws "is being added too late" and the whole biome fails (#264).
 *
 * <p>Registration is still open at that point: the vanilla registries stay writable until loading
 * finishes, only Forge's per-registry flag is set. So the call reopens that one registry, registers
 * and locks it again. A registry is reopened only before Forge's {@code FREEZE_DATA} loading state
 * completes. After that the ids are final and may already be synced to clients, so a late call
 * fails exactly as before. When the loading state cannot be read, the registry is left locked.
 * Forge's registry and loader APIs are never obfuscated, so plain reflection is safe on every
 * Forge host.
 */
public final class LateForgeRegistration {

    private LateForgeRegistration() {}

    /** {@code registry.register(name, value)}, reopening a registry Forge locked after its event. */
    public static void register(Object registry, Object name, Object value) throws Throwable {
        Method register = registerMethod(name);
        try {
            invokeReopening(registry, stillLoading(), () -> register.invoke(registry, name, value));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Run {@code action}, reopening a locked registry around it only while loading. */
    static void invokeReopening(Object registry, boolean loading, Callable<?> action) throws Exception {
        boolean reopened = loading && reopenIfLocked(registry);
        try {
            action.call();
        } finally {
            if (reopened) registry.getClass().getMethod("freeze").invoke(registry);
        }
    }

    private static Method registerMethod(Object name) throws ReflectiveOperationException {
        Class<?> registryApi = Class.forName("net.minecraftforge.registries.IForgeRegistry");
        for (Method method : registryApi.getMethods()) {
            if (method.getName().equals("register") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0].isInstance(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException("IForgeRegistry.register(" + name.getClass().getName() + ", Object)");
    }

    private static boolean reopenIfLocked(Object registry) {
        try {
            Method isLocked = registry.getClass().getMethod("isLocked");
            if (!Boolean.TRUE.equals(isLocked.invoke(registry))) return false;
            registry.getClass().getMethod("unfreeze").invoke(registry);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not a lockable Forge registry: register normally and let it report its own error.
            return false;
        }
    }

    /** Whether Forge has yet to freeze its registry data for this session. */
    private static boolean stillLoading() {
        try {
            Class<?> modLoader = Class.forName("net.minecraftforge.fml.ModLoader");
            Object loader = modLoader.getMethod("get").invoke(null);
            Object frozen = modLoader.getMethod("hasCompletedState", String.class)
                    .invoke(loader, "FREEZE_DATA");
            return Boolean.FALSE.equals(frozen);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }
}
