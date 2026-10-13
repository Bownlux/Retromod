/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * {@code ObfuscationReflectionHelper.findMethod} for mods built before 1.19.
 *
 * <p>Those mods registered their point of interest's block states themselves by calling the
 * private {@code PoiType.registerBlockStates} found this way. 1.19 made {@code PoiType} a record,
 * removed that method, and has Forge and NeoForge register the states of every registered point of
 * interest, so the lookup threw and stopped the mod's setup. That one lookup now answers with a
 * method that does nothing. Every other lookup goes to the loader's helper unchanged.
 */
public final class LegacyPoiLookups {
    private LegacyPoiLookups() {}

    private static final Set<String> REGISTER_BLOCK_STATES = Set.of("registerBlockStates", "m_27367_", "func_221052_a");
    private static final String[] HELPERS = {"net.minecraftforge.fml.util.ObfuscationReflectionHelper",
            "net.neoforged.fml.util.ObfuscationReflectionHelper"};

    public static Method findMethod(Class<?> owner, String name, Class<?>... parameters) {
        if (owner != null && owner.getName().endsWith(".PoiType") && REGISTER_BLOCK_STATES.contains(name)) {
            try {
                return LegacyPoiLookups.class.getMethod("registerBlockStatesDone", Object.class);
            } catch (NoSuchMethodException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        Method helper = loaderHelper();
        try {
            return (Method) helper.invoke(null, owner, name, parameters);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failed.getCause());
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    /** What the old lookup found; the loader already registered these block states. */
    public static void registerBlockStatesDone(Object poiType) {
    }

    private static Method loaderHelper() {
        for (String name : HELPERS) {
            try {
                Class<?> helper = Class.forName(name, false, LegacyPoiLookups.class.getClassLoader());
                return helper.getMethod("findMethod", Class.class, String.class, Class[].class);
            } catch (ClassNotFoundException | NoSuchMethodException absent) {
                // the other loader's helper
            }
        }
        throw new IllegalStateException("Retromod could not find the loader's ObfuscationReflectionHelper");
    }
}
