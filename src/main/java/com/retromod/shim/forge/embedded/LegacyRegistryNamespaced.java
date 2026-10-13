/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.Collections;

/**
 * Stands in for 1.12.2's {@code RegistryNamespaced}, the type of {@code Block.REGISTRY},
 * {@code Item.REGISTRY}, {@code SoundEvent.REGISTRY}, {@code Biome.REGISTRY},
 * {@code Potion.REGISTRY}, and {@code Enchantment.REGISTRY}. 1.13 replaced those static fields with
 * registries, and a mod that looked an entry up by name failed with {@code NoSuchFieldError} as soon
 * as it touched one.
 *
 * <p>Lookups read through to the current built-in registry. Biomes became data in 1.16 and only
 * exist once a world loads, so that view answers empty. Reference parameters are {@code Object}
 * because calls on stand-ins are erased to that shape.
 */
public final class LegacyRegistryNamespaced {

    private final String registryField;
    private volatile Object registry;

    private LegacyRegistryNamespaced(String registryField) {
        this.registryField = registryField;
    }

    public static LegacyRegistryNamespaced blocks() { return new LegacyRegistryNamespaced("BLOCK"); }
    public static LegacyRegistryNamespaced items() { return new LegacyRegistryNamespaced("ITEM"); }
    public static LegacyRegistryNamespaced soundEvents() { return new LegacyRegistryNamespaced("SOUND_EVENT"); }
    public static LegacyRegistryNamespaced mobEffects() { return new LegacyRegistryNamespaced("MOB_EFFECT"); }
    public static LegacyRegistryNamespaced potions() { return new LegacyRegistryNamespaced("POTION"); }
    public static LegacyRegistryNamespaced biomes() { return new LegacyRegistryNamespaced(null); }

    /** {@code getObject(name)}: the entry, or null. */
    public Object getObject(Object name) {
        Object registry = registry();
        if (registry == null || name == null) return null;
        Object value = invoke(registry, "getValue", name);
        return value != null ? value : invoke(registry, "get", name);
    }

    /** {@code getNameForObject(value)}: the entry's name, or null. */
    public Object getNameForObject(Object value) {
        Object registry = registry();
        return registry == null || value == null ? null : invoke(registry, "getKey", value);
    }

    /** {@code containsKey(name)}. */
    public boolean containsKey(Object name) {
        Object registry = registry();
        return registry != null && Boolean.TRUE.equals(invoke(registry, "containsKey", name));
    }

    /** {@code getKeys()}: every registered name. */
    public Object getKeys() {
        Object registry = registry();
        Object keys = registry == null ? null : invoke(registry, "keySet", null);
        return keys != null ? keys : Collections.emptySet();
    }

    /** {@code getIDForObject(value)}: the numeric id, or -1. */
    public int getIDForObject(Object value) {
        Object registry = registry();
        Object id = registry == null ? null : invoke(registry, "getId", value);
        return id instanceof Integer number ? number : -1;
    }

    /** {@code getObjectById(id)}: the entry with that numeric id, or null. */
    public Object getObjectById(int id) {
        Object registry = registry();
        if (registry == null) return null;
        for (Method method : registry.getClass().getMethods()) {
            if (method.getName().equals("byId") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class) {
                try {
                    return method.invoke(registry, id);
                } catch (ReflectiveOperationException | RuntimeException failed) {
                    return null;
                }
            }
        }
        return null;
    }

    // The SRG table names some of these methods by their current names, so both spellings exist.
    public Object get(Object name) { return getObject(name); }
    public Object getKey(Object value) { return getNameForObject(value); }
    public Object keySet() { return getKeys(); }
    public int getId(Object value) { return getIDForObject(value); }
    public Object byId(int id) { return getObjectById(id); }

    /**
     * The entries, for a for-each loop. Declared as {@code Object} because calls on stand-ins are
     * erased to that shape and cast back to {@code Iterator} at the call.
     */
    public Object iterator() {
        Object registry = registry();
        return registry instanceof Iterable<?> entries ? entries.iterator() : Collections.emptyIterator();
    }

    private Object registry() {
        if (registryField == null) return null;
        Object known = registry;
        if (known != null) return known;
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true,
                    LegacyRegistryNamespaced.class.getClassLoader());
            known = registries.getField(registryField).get(null);
            registry = known;
            return known;
        } catch (ReflectiveOperationException | LinkageError missing) {
            return null;
        }
    }

    private static Object invoke(Object target, String name, Object argument) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != (argument == null ? 0 : 1)) continue;
            if (argument != null && !method.getParameterTypes()[0].isInstance(argument)) continue;
            try {
                return argument == null ? method.invoke(target) : method.invoke(target, argument);
            } catch (ReflectiveOperationException | RuntimeException failed) {
                return null;
            }
        }
        return null;
    }
}
