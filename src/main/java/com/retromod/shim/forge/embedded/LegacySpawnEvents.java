/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;

import java.lang.reflect.Method;
import java.util.Optional;

/** Forge's {@code MobSpawnEvent.FinalizeSpawn} calls that NeoForge's {@code FinalizeSpawnEvent} answers differently. */
public final class LegacySpawnEvents {

    private LegacySpawnEvents() {}

    /** Forge's {@code getSpawner()}: the spawner logic of the block entity or minecart, or null for a natural spawn. */
    public static Object getSpawner(Object event) {
        Object either = call(event, "getSpawner");
        if (either == null) return null;
        // NeoForge hands out Either<BlockEntity, Entity>; both spawner kinds keep their logic behind getSpawner().
        for (String side : new String[] {"left", "right"}) {
            Object found = call(either, side);
            if (found instanceof Optional<?> optional && optional.isPresent()) {
                return spawnerOf(optional.get());
            }
        }
        return null;
    }

    /** Forge's {@code getSpawnTag()}: NeoForge's event carries no spawn tag, so there is none to read. */
    public static Object getSpawnTag(Object event) {
        return null;
    }

    /** Forge's {@code setSpawnTag(tag)}: NeoForge passes no tag to finalizeSpawn, so the value has nowhere to go. */
    public static void setSpawnTag(Object event, Object tag) {
    }

    private static Object spawnerOf(Object source) {
        Method getter = find(source, "getSpawner", 0);
        // A trial spawner or a mod's own spawner source has no old BaseSpawner to give.
        if (getter == null || !getter.getReturnType().getName().equals("net.minecraft.world.level.BaseSpawner")) return null;
        return invoke(getter, source);
    }

    private static Object call(Object target, String name) {
        Method method = find(target, name, 0);
        return method == null ? null : invoke(method, target);
    }

    private static Method find(Object target, String name, int parameters) {
        for (Method method : LegacyPublicMethods.methods(target.getClass())) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + method + " on " + target, e);
        }
    }
}
