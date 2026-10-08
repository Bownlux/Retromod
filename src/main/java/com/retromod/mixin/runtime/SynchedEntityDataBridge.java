/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Stands in for two {@code SynchedEntityData} fields that 1.20.5 removed.
 *
 * <p>Before 1.20.5 the entity data kept its items in an {@code Int2ObjectMap<DataItem<?>>} named
 * {@code itemsById} and guarded it with a {@code ReadWriteLock} named {@code lock}. Since then the
 * items sit in a {@code DataItem<?>[]} indexed by id and there is no lock, because entity data is
 * only touched on the owning thread. Mods that read every item through accessors (for example to
 * build a full sync packet) are pointed here by
 * {@link com.retromod.mixin.MixinAccessorFieldMigration}.
 *
 * <p>{@link #itemsById} returns a fresh map of the current items keyed by id. It is a snapshot:
 * reads see every item, but puts and removes do not change the entity. {@link #lock} returns one
 * shared lock, so a mod's matching lock and unlock calls pair up and never block the game.
 *
 * <p>No compile-time Minecraft or fastutil dependency, for the same per-mod embedding reason as
 * {@link AreaEffectCloudEffectsBridge}. When the item array cannot be read the map is empty. It
 * is null only if fastutil itself cannot be loaded, which no Minecraft host allows.
 */
public final class SynchedEntityDataBridge {

    private static final String DATA_ITEM = "net.minecraft.network.syncher.SynchedEntityData$DataItem";
    private static final String INT_MAP = "it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap";
    private static final ReadWriteLock SHARED_LOCK = new ReentrantReadWriteLock();

    private SynchedEntityDataBridge() {}

    /** The entity data's items as an {@code Int2ObjectMap} snapshot keyed by id. */
    public static Object itemsById(Object entityData) {
        Object map;
        try {
            map = newIntMap(entityData);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
        Object items = readItemArray(entityData);
        if (items == null) return map;
        try {
            Method put = map.getClass().getMethod("put", int.class, Object.class);
            for (int id = 0; id < Array.getLength(items); id++) {
                Object item = Array.get(items, id);
                if (item != null) put.invoke(map, id, item);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Keep whatever was copied; a partial snapshot is still safe to iterate.
        }
        return map;
    }

    /** One shared lock standing in for the removed per-entity lock. */
    public static Object lock(Object entityData) {
        return SHARED_LOCK;
    }

    private static Object newIntMap(Object entityData) throws ReflectiveOperationException {
        ClassLoader loader = entityData == null ? null : entityData.getClass().getClassLoader();
        Class<?> mapType = Class.forName(INT_MAP, false,
                loader != null ? loader : SynchedEntityDataBridge.class.getClassLoader());
        return mapType.getConstructor().newInstance();
    }

    private static Object readItemArray(Object entityData) {
        if (entityData == null) return null;
        for (Class<?> type = entityData.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (!fieldType.isArray() || !DATA_ITEM.equals(fieldType.getComponentType().getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.get(entityData);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
