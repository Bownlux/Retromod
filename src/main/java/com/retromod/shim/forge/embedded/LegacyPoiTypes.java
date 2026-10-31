/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** A Forge mod's own {@code PoiTypes.registerBlockStates} call, on NeoForge, which already registers them. */
public final class LegacyPoiTypes {

    private static final String POI_TYPES = "net.minecraft.world.entity.ai.village.poi.PoiTypes";

    private LegacyPoiTypes() {}

    /**
     * Forge left a modded point of interest's block states unregistered, so mods registered them
     * from their setup. NeoForge registers them with the type, and a second registration fails
     * with "defined in more than one PoI type", so only states no type claims yet are added.
     */
    public static void registerBlockStates(Object poi, Set<?> states) {
        try {
            ClassLoader loader = poi.getClass().getClassLoader();
            Class<?> types = Class.forName(POI_TYPES, true, loader);
            Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState", true, loader);
            Method forState = types.getMethod("forState", blockState);
            Set<Object> unclaimed = new LinkedHashSet<>();
            for (Object state : states) {
                if (((Optional<?>) forState.invoke(null, state)).isEmpty()) unclaimed.add(state);
            }
            if (unclaimed.isEmpty()) return;
            Method register = types.getDeclaredMethod("registerBlockStates",
                    Class.forName("net.minecraft.core.Holder", true, loader), Set.class);
            register.setAccessible(true);
            register.invoke(null, poi, unclaimed);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not register the block states of " + poi, e);
        }
    }
}
