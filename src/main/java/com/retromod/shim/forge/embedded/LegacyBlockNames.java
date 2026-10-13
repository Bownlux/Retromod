/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;

/**
 * 1.12's {@code Block.getBlockFromName(name)} and {@code Item.getByNameOrId(name)}, answered from
 * the built-in registries. An unknown name gives null, as before.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyBlockNames {
    private LegacyBlockNames() {}

    /** The block registered under {@code name}, such as {@code minecraft:stone} or {@code stone}. */
    public static Object block(Object name) {
        return lookup("BLOCK", name);
    }

    /** The item registered under {@code name}. */
    public static Object item(Object name) {
        return lookup("ITEM", name);
    }

    private static Object lookup(String registryField, Object name) {
        if (name == null) return null;
        try {
            ClassLoader loader = LegacyBlockNames.class.getClassLoader();
            Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                    .getField(registryField).get(null);
            Class<?> idClass = idClass(loader);
            Object id = idClass.getMethod("tryParse", String.class).invoke(null, String.valueOf(name));
            if (id == null) return null;
            // 1.21 spells it containsKey/get; 26.x spells it containsKey/getValue.
            Method contains = registry.getClass().getMethod("containsKey", idClass);
            if (!(Boolean) contains.invoke(registry, id)) return null;
            for (String getter : new String[] {"getValue", "get"}) {
                for (Method method : registry.getClass().getMethods()) {
                    if (method.getName().equals(getter) && method.getParameterCount() == 1
                            && method.getParameterTypes()[0] == idClass) {
                        return method.invoke(registry, id);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    private static Class<?> idClass(ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft.resources.Identifier", false, loader);
        } catch (ClassNotFoundException olderHost) {
            return Class.forName("net.minecraft.resources.ResourceLocation", false, loader);
        }
    }
}
