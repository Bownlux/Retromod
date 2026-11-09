/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.*;

/**
 * Reflective Entity accessors that ride out MC API drift (the 1.21.9
 * getWorld -> getEntityWorld rename) without a compile-time MC dependency.
 */
public class EntityShim {
    
    private static Method getWorldMethod = null;
    private static Method getEntityWorldMethod = null;
    private static Method getBlockPosMethod = null;
    private static boolean methodsInitialized = false;
    
    /** Entity block position, trying getBlockPos / getPosition across versions. */
    public static Object getBlockPos(Object entity) {
        if (entity == null) return null;

        try {
            initMethods(entity.getClass());

            if (getBlockPosMethod != null) {
                return getBlockPosMethod.invoke(entity);
            }

            for (String methodName : new String[]{"getBlockPos", "getPosition", "blockPosition"}) {
                try {
                    Method m = LegacyPublicMethods.method(entity.getClass(), methodName);
                    return m.invoke(entity);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Retromod: EntityShim.getBlockPos error: " + e.getMessage());
        }
        return null;
    }
    
    /** Entity world, handling the 1.21.9 getWorld -> getEntityWorld rename. */
    public static Object getWorld(Object entity) {
        if (entity == null) return null;

        try {
            initMethods(entity.getClass());

            if (getEntityWorldMethod != null) {
                return getEntityWorldMethod.invoke(entity);
            }

            if (getWorldMethod != null) {
                return getWorldMethod.invoke(entity);
            }

            for (String methodName : new String[]{"getEntityWorld", "getWorld", "level", "getLevel"}) {
                try {
                    Method m = LegacyPublicMethods.method(entity.getClass(), methodName);
                    return m.invoke(entity);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Retromod: EntityShim.getWorld error: " + e.getMessage());
        }
        return null;
    }
    
    public static double getX(Object entity) {
        return getDoubleField(entity, "getX", "getPosX", "posX");
    }

    public static double getY(Object entity) {
        return getDoubleField(entity, "getY", "getPosY", "posY");
    }

    public static double getZ(Object entity) {
        return getDoubleField(entity, "getZ", "getPosZ", "posZ");
    }

    public static float getYaw(Object entity) {
        return getFloatField(entity, "getYaw", "getRotationYaw", "rotationYaw");
    }

    public static float getPitch(Object entity) {
        return getFloatField(entity, "getPitch", "getRotationPitch", "rotationPitch");
    }

    public static boolean isOnGround(Object entity) {
        return getBooleanField(entity, "isOnGround", "onGround");
    }

    public static Object getUUID(Object entity) {
        return invokeMethod(entity, "getUuid", "getUniqueID", "getUUID");
    }

    public static Object getDisplayName(Object entity) {
        return invokeMethod(entity, "getDisplayName", "getName", "getCustomName");
    }

    private static void initMethods(Class<?> entityClass) {
        if (methodsInitialized) return;

        try {
            try {
                getEntityWorldMethod = LegacyPublicMethods.method(entityClass, "getEntityWorld");
            } catch (NoSuchMethodException ignored) {}

            try {
                getWorldMethod = LegacyPublicMethods.method(entityClass, "getWorld");
            } catch (NoSuchMethodException ignored) {}

            try {
                getBlockPosMethod = LegacyPublicMethods.method(entityClass, "getBlockPos");
            } catch (NoSuchMethodException ignored) {}

            methodsInitialized = true;
        } catch (Exception e) {
            System.err.println("Retromod: EntityShim init error: " + e.getMessage());
        }
    }
    
    private static double getDoubleField(Object obj, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = LegacyPublicMethods.method(obj.getClass(), name);
                return (double) m.invoke(obj);
            } catch (Exception ignored) {}

            try {
                Field f = obj.getClass().getField(name);
                return f.getDouble(obj);
            } catch (Exception ignored) {}
        }
        return 0.0;
    }
    
    private static float getFloatField(Object obj, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = LegacyPublicMethods.method(obj.getClass(), name);
                return (float) m.invoke(obj);
            } catch (Exception ignored) {}
            
            try {
                Field f = obj.getClass().getField(name);
                return f.getFloat(obj);
            } catch (Exception ignored) {}
        }
        return 0.0f;
    }
    
    private static boolean getBooleanField(Object obj, String... names) {
        for (String name : names) {
            try {
                Method m = LegacyPublicMethods.method(obj.getClass(), name);
                return (boolean) m.invoke(obj);
            } catch (Exception ignored) {}
            
            try {
                Field f = obj.getClass().getField(name);
                return f.getBoolean(obj);
            } catch (Exception ignored) {}
        }
        return false;
    }
    
    private static Object invokeMethod(Object obj, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = LegacyPublicMethods.method(obj.getClass(), name);
                return m.invoke(obj);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
