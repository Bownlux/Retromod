/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;

/**
 * {@code SpawnPlacements.register(type, placement, heightmap, predicate)}, which Forge opened to mods
 * and NeoForge keeps private behind its {@code RegisterSpawnPlacementsEvent}. An older mod called it
 * from a setup task, so the call is made here, where the game's own method is reached reflectively.
 * Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacySpawnPlacements {
    private LegacySpawnPlacements() {}

    public static void register(Object entityType, Object placementType, Object heightmap, Object predicate) {
        try {
            Class<?> placements = Class.forName("net.minecraft.world.entity.SpawnPlacements", true,
                    LegacySpawnPlacements.class.getClassLoader());
            for (Method method : placements.getDeclaredMethods()) {
                if (method.getName().equals("register") && method.getParameterCount() == 4) {
                    method.setAccessible(true);
                    method.invoke(null, entityType, placementType, heightmap, predicate);
                    return;
                }
            }
            throw new NoSuchMethodException("SpawnPlacements.register");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("[Retromod] Could not register the spawn placement of " + entityType
                    + ", so it uses the default: " + cause);
        }
    }
}
