/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads another mod's registry entry field for a Forge mod running on NeoForge.
 *
 * <p>A Forge addon reads its library's entries from {@code RegistryObject} fields, and Retromod
 * turns those reads into {@code DeferredHolder} reads. The library's own NeoForge port usually
 * declares the same fields as {@code Supplier}, {@code Holder} or {@code DeferredHolder}, chosen
 * field by field, so the addon's read fails with {@code NoSuchFieldError}. Farmer's Delight 1.21.1
 * uses all three. The value behind each type is still the {@code DeferredHolder} that
 * {@code DeferredRegister.register} returned, so the field is found by name and that value is
 * handed back. A {@code Holder} the library made some other way is looked up again by its key.
 *
 * <p>Embedded in each translated mod, so the reflection runs with that mod's view of the game.
 */
public final class LibraryRegistryFields {

    private static final String DEFERRED_HOLDER = "net.neoforged.neoforge.registries.DeferredHolder";
    private static final String HOLDER = "net.minecraft.core.Holder";
    private static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";

    /**
     * Entries of final fields already read, so a read in a hot path is a map lookup. Only this
     * class is embedded, so it must not need a nested class of its own, such as a ClassValue.
     */
    private static final Map<Class<?>, Map<String, Object>> READ = new ConcurrentHashMap<>();

    private LibraryRegistryFields() {}

    /** {@code owner.name} as the {@code DeferredHolder} the Forge mod was written to read. */
    public static Object read(Class<?> owner, String name) {
        Map<String, Object> read = READ.computeIfAbsent(owner, type -> new ConcurrentHashMap<>());
        Object known = read.get(name);
        if (known != null) return known;
        Field field = staticField(owner, name);
        Object entry = asDeferredHolder(value(field), field);
        // A final field keeps its value once set. It is still null while its class initializes.
        if (entry != null && Modifier.isFinal(field.getModifiers())) read.put(name, entry);
        return entry;
    }

    /** The field a {@code GETSTATIC} of {@code owner.name} resolves to, with the errors it would throw. */
    private static Field staticField(Class<?> owner, String name) {
        Field field = find(owner, name);
        if (field == null) throw new NoSuchFieldError(owner.getName() + "." + name);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IncompatibleClassChangeError("Expected static field " + owner.getName() + "." + name);
        }
        // A protected field of a library base class is readable from the mod's subclass, not from here.
        if (!field.canAccess(null)) field.trySetAccessible();
        return field;
    }

    private static Field find(Class<?> owner, String name) {
        try {
            // Public fields of the class, its interfaces and its superclasses, in resolution order.
            return owner.getField(name);
        } catch (NoSuchFieldException notPublic) {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                try {
                    return type.getDeclaredField(name);
                } catch (NoSuchFieldException notHere) {
                    // A superclass may declare it.
                }
            }
            return null;
        }
    }

    private static Object value(Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    private static Object asDeferredHolder(Object value, Field field) {
        if (value == null) return null;
        ClassLoader loader = LibraryRegistryFields.class.getClassLoader();
        Class<?> deferredHolder = type(DEFERRED_HOLDER, loader);
        if (deferredHolder.isInstance(value)) return value;
        Class<?> holder = type(HOLDER, loader);
        if (holder.isInstance(value)) {
            try {
                Optional<?> key = (Optional<?>) holder.getMethod("unwrapKey").invoke(value);
                if (key.isPresent()) {
                    return deferredHolder.getMethod("create", type(RESOURCE_KEY, loader)).invoke(null, key.get());
                }
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("Could not look up " + describe(field) + " by its key", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not look up " + describe(field) + " by its key", e);
            }
        }
        throw new IncompatibleClassChangeError(describe(field) + " holds a " + value.getClass().getName()
                + ", which a Forge mod cannot read as a RegistryObject. Use a build of the mod reading it"
                + " made for this Minecraft version.");
    }

    private static Class<?> type(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(name.replace('.', '/'));
        }
    }

    private static String describe(Field field) {
        return field.getDeclaringClass().getName() + "." + field.getName();
    }
}
