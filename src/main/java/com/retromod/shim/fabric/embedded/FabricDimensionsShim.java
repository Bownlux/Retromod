/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;

/**
 * Bridges FabricDimensions.teleport(), dropped in 1.21.2+ for vanilla Entity#teleportTo.
 */
public final class FabricDimensionsShim {

    private FabricDimensionsShim() {
    }

    /**
     * Teleport an entity to another dimension. The returned entity may be a different
     * instance than the one passed in (players get re-created across dimensions).
     */
    @SuppressWarnings("unchecked")
    public static <E> E teleport(E entity, Object destination, Object target) {
        try {
            double x, y, z;
            float yaw, pitch;

            Class<?> targetClass = target.getClass();

            Method posMethod = findMethod(targetClass, "position", "pos", "getPos");
            Object pos = posMethod.invoke(target);

            Class<?> vecClass = pos.getClass();
            x = getDouble(pos, vecClass, "x", "getX");
            y = getDouble(pos, vecClass, "y", "getY");
            z = getDouble(pos, vecClass, "z", "getZ");

            yaw = getFloat(target, targetClass, "yaw", "getYaw");
            pitch = getFloat(target, targetClass, "pitch", "getPitch");

            Class<?> entityClass = entity.getClass();

            try {
                Method teleportTo = findMethod(entityClass, "teleportTo");
                if (teleportTo != null && teleportTo.getParameterCount() >= 6) {
                    teleportTo.invoke(entity, destination, x, y, z,
                        java.util.Set.of(), yaw, pitch, false);
                    return entity;
                }
            } catch (Exception ignored) {}

            try {
                Method changeDim = LegacyPublicMethods.method(entityClass, "changeDimension", destination.getClass());
                Object result = changeDim.invoke(entity, destination);

                if (result != null) {
                    Method setPos = LegacyPublicMethods.method(result.getClass(), "setPos", double.class, double.class, double.class);
                    setPos.invoke(result, x, y, z);
                    
                    Method setRot = findMethod(result.getClass(), "setYaw", "setRotation");
                    if (setRot != null) {
                        if (setRot.getParameterCount() == 2) {
                            setRot.invoke(result, yaw, pitch);
                        } else {
                            setRot.invoke(result, yaw);
                            findMethod(result.getClass(), "setPitch").invoke(result, pitch);
                        }
                    }
                    
                    return (E) result;
                }
            } catch (Exception ignored) {}

            // last resort: move in place, no dimension change
            Method setPos = LegacyPublicMethods.method(entityClass, "setPos", double.class, double.class, double.class);
            setPos.invoke(entity, x, y, z);

            return entity;

        } catch (Exception e) {
            throw new RuntimeException("Retromod: Failed to teleport entity", e);
        }
    }
    
    private static Method findMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            for (Method m : LegacyPublicMethods.methods(clazz)) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
        }
        return null;
    }
    
    private static double getDouble(Object obj, Class<?> clazz, String... names) throws Exception {
        for (String name : names) {
            try {
                var field = clazz.getField(name);
                return field.getDouble(obj);
            } catch (NoSuchFieldException ignored) {}
        }

        Method m = findMethod(clazz, names);
        if (m != null) {
            return ((Number) m.invoke(obj)).doubleValue();
        }

        return 0.0;
    }

    private static float getFloat(Object obj, Class<?> clazz, String... names) throws Exception {
        for (String name : names) {
            try {
                var field = clazz.getField(name);
                return field.getFloat(obj);
            } catch (NoSuchFieldException ignored) {}
        }

        Method m = findMethod(clazz, names);
        if (m != null) {
            return ((Number) m.invoke(obj)).floatValue();
        }

        return 0.0f;
    }
}
