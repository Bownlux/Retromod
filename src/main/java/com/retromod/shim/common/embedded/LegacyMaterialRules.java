/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;

/**
 * The old {@code SurfaceRules.isBiome(keys)}, which 26.3's {@code MaterialRules.isBiome} replaced
 * with a version that also takes the biome lookup. The lookup comes from the running server, which
 * is where mods built these rules: in a server start handler. Reflective, because this class is
 * embedded into each mod and compiled without Minecraft.
 */
public final class LegacyMaterialRules {
    private LegacyMaterialRules() {}

    public static Object isBiome(Object[] biomeKeys) {
        try {
            ClassLoader loader = LegacyMaterialRules.class.getClassLoader();
            Object server = currentServer(loader);
            if (server == null) {
                throw new IllegalStateException("a biome surface rule can only be built while a server is running");
            }
            Object access = server.getClass().getMethod("registryAccess").invoke(server);
            Object biomeRegistry = Class.forName("net.minecraft.core.registries.Registries", true, loader)
                    .getField("BIOME").get(null);
            Object lookup = null;
            for (Method method : access.getClass().getMethods()) {
                if (method.getName().equals("lookupOrThrow") && method.getParameterCount() == 1) {
                    lookup = method.invoke(access, biomeRegistry);
                    break;
                }
            }
            Class<?> rules = Class.forName("net.minecraft.world.level.levelgen.material.MaterialRules", true, loader);
            for (Method method : rules.getMethods()) {
                if (method.getName().equals("isBiome") && method.getParameterCount() == 2) {
                    Object keys = java.lang.reflect.Array.newInstance(method.getParameterTypes()[1].getComponentType(),
                            biomeKeys.length);
                    System.arraycopy(biomeKeys, 0, keys, 0, biomeKeys.length);
                    return method.invoke(null, lookup, keys);
                }
            }
            throw new NoSuchMethodException("MaterialRules.isBiome");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build a biome surface rule", e);
        }
    }

    private static Object currentServer(ClassLoader loader) {
        for (String hooks : new String[] {"net.neoforged.neoforge.server.ServerLifecycleHooks",
                "net.minecraftforge.server.ServerLifecycleHooks"}) {
            try {
                return Class.forName(hooks, true, loader).getMethod("getCurrentServer").invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                // not this loader
            }
        }
        return null;
    }
}
