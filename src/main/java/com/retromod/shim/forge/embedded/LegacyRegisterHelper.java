/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;

/**
 * Forge's {@code RegisterEvent.RegisterHelper.register(String name, value)}, which NeoForge does
 * not have. Forge named the entry in the namespace of the mod whose listener was running, so the
 * same id is built here and passed to the helper's {@code register(id, value)}.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyRegisterHelper {
    private LegacyRegisterHelper() {}

    public static void register(Object helper, String name, Object value) {
        ClassLoader loader = LegacyRegisterHelper.class.getClassLoader();
        try {
            Object context = Class.forName("net.neoforged.fml.ModLoadingContext", true, loader).getMethod("get").invoke(null);
            String namespace = (String) context.getClass().getMethod("getActiveNamespace").invoke(context);
            Class<?> idType = idType(loader);
            Object id = idType.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, name);
            Method register = Class.forName("net.neoforged.neoforge.registries.RegisterEvent$RegisterHelper", false, loader)
                    .getMethod("register", idType, Object.class);
            register.invoke(helper, id, value);
        } catch (java.lang.reflect.InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not register " + name, failed.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not register " + name + " through NeoForge", e);
        }
    }

    private static Class<?> idType(ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft.resources.Identifier", false, loader);
        } catch (ClassNotFoundException olderHost) {
            return Class.forName("net.minecraft.resources.ResourceLocation", false, loader);
        }
    }
}
