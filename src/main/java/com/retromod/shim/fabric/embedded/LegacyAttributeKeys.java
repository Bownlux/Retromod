/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime half of the attribute lookups in
 * {@link com.retromod.shim.fabric.Pre1_20_5RegistryHolderBridge}: re-keys an entity type's default
 * attributes when a pre-1.20.5 mod added an attribute before registering it.
 *
 * <p>Before 1.20.5 default attributes were keyed by the bare {@code Attribute}, so it did not
 * matter whether a mod registered its attribute before or after the defaults were built. They are
 * now keyed by registry holder. A mod that adds its attribute to {@code createLivingAttributes()}
 * and registers it in its initializer can have the defaults built in between, for example when
 * another mod's initializer runs first. The defaults then hold a direct holder, the mod's lookups
 * use the registered holder, and they find nothing: the attribute reads as missing and the mod's
 * code fails on a null instance.
 *
 * <p>On such a miss the defaults' entry for the same attribute is moved to the registered holder,
 * once per entity type. The instances created from it then carry the registered holder, so they
 * save with the attribute's id. Nothing changes when the lookup already succeeds.
 *
 * <p>Everything here is reflective and keyed on intermediary names, because this class is embedded
 * into old Fabric mods on pre-26.1 hosts and Retromod does not compile against Minecraft. Every
 * attribute lookup of an old mod passes through here, often each tick, so the reflective members
 * are resolved once per class. The caches are plain maps rather than a {@code ClassValue}, because
 * the embedder copies this one class and a nested class would be left behind.
 */
public final class LegacyAttributeKeys {

    private static final String HOLDER_VALUE = "comp_349";
    private static final String GET_ATTRIBUTES = "method_6127";
    private static final String ATTRIBUTE_SUPPLIER = "net.minecraft.class_5132";

    private static final Map<Class<?>, Method> GET_ATTRIBUTES_BY_CLASS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> HOLDER_VALUE_BY_CLASS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> SUPPLIER_FIELD_BY_CLASS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> INSTANCES_FIELD_BY_CLASS = new ConcurrentHashMap<>();

    private LegacyAttributeKeys() {}

    /** Re-keys the defaults behind {@code livingEntity.getAttributes()} for {@code holder}. */
    public static void repairEntity(Object livingEntity, Object holder) {
        if (livingEntity == null) return;
        try {
            Method getAttributes = GET_ATTRIBUTES_BY_CLASS.get(livingEntity.getClass());
            if (getAttributes == null) {
                getAttributes = livingEntity.getClass().getMethod(GET_ATTRIBUTES);
                GET_ATTRIBUTES_BY_CLASS.put(livingEntity.getClass(), getAttributes);
            }
            repairMap(getAttributes.invoke(livingEntity), holder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The lookup proceeds unrepaired and reports a missing attribute the usual way.
        }
    }

    /** Re-keys the defaults behind an {@code AttributeMap} for {@code holder}. */
    public static void repairMap(Object attributeMap, Object holder) {
        if (attributeMap == null) return;
        try {
            repairSupplier(fieldOfType(attributeMap, ATTRIBUTE_SUPPLIER, SUPPLIER_FIELD_BY_CLASS)
                    .get(attributeMap), holder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // As above: leave the lookup to fail the way it would without Retromod.
        }
    }

    /** Re-keys an {@code AttributeSupplier}'s instances for {@code holder}. */
    public static void repairSupplier(Object supplier, Object holder) {
        if (supplier == null || holder == null) return;
        try {
            Field instancesField = fieldOfType(supplier, Map.class.getName(), INSTANCES_FIELD_BY_CLASS);
            synchronized (supplier) {
                Map<?, ?> instances = (Map<?, ?>) instancesField.get(supplier);
                if (instances.containsKey(holder)) return;
                Object stale = staleKeyFor(instances, holder);
                if (stale == null) return;
                Map<Object, Object> rekeyed = new LinkedHashMap<>();
                instances.forEach((key, instance) -> rekeyed.put(key == stale ? holder : key, instance));
                instancesField.set(supplier, Collections.unmodifiableMap(rekeyed));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // As above.
        }
    }

    /** A key of another holder kind that wraps the same attribute, or null. */
    private static Object staleKeyFor(Map<?, ?> instances, Object holder) throws ReflectiveOperationException {
        Object attribute = valueOf(holder);
        for (Object key : instances.keySet()) {
            if (key != null && key.getClass() != holder.getClass() && valueOf(key) == attribute) {
                return key;
            }
        }
        return null;
    }

    private static Object valueOf(Object holder) throws ReflectiveOperationException {
        Method value = HOLDER_VALUE_BY_CLASS.get(holder.getClass());
        if (value == null) {
            value = findMethod(holder.getClass(), HOLDER_VALUE);
            value.setAccessible(true);
            HOLDER_VALUE_BY_CLASS.put(holder.getClass(), value);
        }
        return value.invoke(holder);
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) return method;
            }
        }
        return type.getMethod(name);
    }

    private static Field fieldOfType(Object owner, String typeName, Map<Class<?>, Field> cache)
            throws NoSuchFieldException {
        Field cached = cache.get(owner.getClass());
        if (cached != null) return cached;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)
                        && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    cache.put(owner.getClass(), field);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(typeName + " in " + owner.getClass().getName());
    }
}
