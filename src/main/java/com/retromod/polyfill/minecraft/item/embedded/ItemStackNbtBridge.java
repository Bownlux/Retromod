/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.item.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** The pre-1.20.5 ItemStack tag calls, answered from the stack's custom data component through reflection. */
public class ItemStackNbtBridge {

    // Every translated jar's copy shares one table, so stacks one mod made private stay private for another.
    private static final String OWNERS_SLOT = "retromod.shared.item-tag-owners.v1";
    // Used only when something else holds the shared slot. ItemStack keeps Object's identity equality.
    private static final Map<Object, Object> PRIVATE_OWNERS = Collections.synchronizedMap(new WeakHashMap<>());
    // The host members this bridge uses, found once for the running game's ItemStack class. A nested holder class
    // would have to be embedded next to this one, so they are plain fields, published by hostStack last.
    private static volatile Class<?> hostStack;
    private static Object customDataType;
    private static Class<?> compoundTagClass;
    private static Method getComponent;
    private static Method setComponent;
    private static Method hasComponent;
    private static Method removeComponent;
    private static Method customDataOf;
    // The stored tag: CustomData.getUnsafe() up to 1.21.4, its one tag field after.
    private static Method getUnsafe;
    private static Field tagField;
    // The stack's component map, and that map's patch and copy-on-write flag; null where the host has none.
    private static Field components;
    private static Field patch;
    private static Field copyOnWrite;

    private static void ensureHost(Object stack) throws ReflectiveOperationException {
        Class<?> stackClass = stack.getClass();
        if (hostStack == stackClass) return;
        synchronized (ItemStackNbtBridge.class) {
            if (hostStack == stackClass) return;
            Class<?> type = Class.forName("net.minecraft.core.component.DataComponentType");
            customDataType = Class.forName("net.minecraft.core.component.DataComponents").getField("CUSTOM_DATA").get(null);
            compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            getComponent = LegacyPublicMethods.method(stackClass, "get", type);
            setComponent = LegacyPublicMethods.method(stackClass, "set", type, Object.class);
            hasComponent = LegacyPublicMethods.method(stackClass, "has", type);
            removeComponent = LegacyPublicMethods.method(stackClass, "remove", type);
            Class<?> customData = Class.forName("net.minecraft.world.item.component.CustomData");
            customDataOf = customData.getMethod("of", compoundTagClass);
            getUnsafe = optionalMethod(customData, "getUnsafe");
            tagField = getUnsafe != null ? null : instanceField(customData, compoundTagClass, false);
            components = instanceField(stackClass, Class.forName("net.minecraft.core.component.PatchedDataComponentMap"), false);
            Class<?> map = components == null ? null : components.getType();
            patch = map == null ? null : instanceField(map, Map.class, true);
            copyOnWrite = map == null ? null : instanceField(map, boolean.class, false);
            hostStack = stackClass;
        }
    }

    private static Method optionalMethod(Class<?> owner, String name) {
        try {
            return LegacyPublicMethods.method(owner, name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /** The one instance field of {@code owner} of type {@code type} (or a subtype when {@code subtypes}), made accessible. */
    private static Field instanceField(Class<?> owner, Class<?> type, boolean subtypes) {
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (subtypes ? type.isAssignableFrom(field.getType()) : field.getType() == type) {
                try {
                    field.setAccessible(true);
                    return field;
                } catch (RuntimeException closed) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * {@code stack.getTag()}: the tag stored in the stack's custom data, made the stack's own first. A copy of a stack
     * shares its custom data instance, so a write through a shared tag would change every copy, such as the copy a menu
     * keeps to notice changes. The stack gets a private copy of its custom data unless it already holds the one this
     * bridge made private for it and has not been copied since.
     */
    public static Object getTag(Object itemStack) {
        try {
            ensureHost(itemStack);
        } catch (ReflectiveOperationException | LinkageError beforeComponents) {
            // Before 1.20.5 the stack has the method itself.
            try {
                return LegacyPublicMethods.method(itemStack.getClass(), "getTag").invoke(itemStack);
            } catch (Exception e2) {
                return null;
            }
        }
        try {
            // Tooltips can be built off the main thread, so one stack's check and copy run together.
            synchronized (itemStack) {
                Object data = getComponent.invoke(itemStack, customDataType);
                if (data == null) return null;
                if (!ownsPrivately(itemStack, data)) data = makePrivate(itemStack, data);
                return data == null ? null : storedTag(data);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether the stack still holds the custom data this bridge made private for it, with no copy since. */
    private static boolean ownsPrivately(Object stack, Object data) throws IllegalAccessException {
        // Without the component map's fields a copy cannot be seen, so every read makes the data private again.
        if (components == null || patch == null || copyOnWrite == null) return false;
        Object record = owners().get(stack);
        if (!(record instanceof Object[] owned) || owned[0] != data) return false;
        Object map = components.get(stack);
        // Copying the stack marks its map copy on write and leaves the patch shared until the next write replaces it.
        return owned[1] == patch.get(map) && !copyOnWrite.getBoolean(map);
    }

    /** Stores a private copy of {@code data} on the stack, records it, and returns it. */
    private static Object makePrivate(Object stack, Object data) throws ReflectiveOperationException {
        Object tag = storedTag(data);
        if (tag == null) return null;
        // CustomData.of copies the tag, so the new instance belongs to this stack alone.
        Object own = customDataOf.invoke(null, tag);
        setComponent.invoke(stack, customDataType, own);
        remember(stack, own);
        return own;
    }

    private static void remember(Object stack, Object data) throws IllegalAccessException {
        if (components == null || patch == null) return;
        // The write that stored the data made the patch the stack's own; that patch marks the record current.
        owners().put(stack, new Object[]{data, patch.get(components.get(stack))});
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> owners() {
        Object shared = System.getProperties().computeIfAbsent(OWNERS_SLOT,
                unused -> Collections.synchronizedMap(new WeakHashMap<>()));
        return shared instanceof Map<?, ?> map ? (Map<Object, Object>) map : PRIVATE_OWNERS;
    }

    /** The tag a custom data instance stores, which a write changes in place, or null when it cannot be reached. */
    private static Object storedTag(Object data) throws ReflectiveOperationException {
        if (getUnsafe != null) return getUnsafe.invoke(data);
        return tagField != null ? tagField.get(data) : null;
    }

    /**
     * Checks whether an ItemStack has NBT data attached.
     * Bridges old hasTag() to DataComponents.CUSTOM_DATA check.
     */
    public static boolean hasTag(Object itemStack) {
        try {
            ensureHost(itemStack);
            return (boolean) hasComponent.invoke(itemStack, customDataType);
        } catch (Exception | LinkageError e) {
            try {
                return (boolean) LegacyPublicMethods.method(itemStack.getClass(), "hasTag").invoke(itemStack);
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /**
     * Sets NBT data on an ItemStack, bridging old setTag() to DataComponents.CUSTOM_DATA.
     * Creates a CustomData wrapper around the CompoundTag for the new system.
     */
    public static void setTag(Object itemStack, Object compoundTag) {
        try {
            ensureHost(itemStack);
            setComponent.invoke(itemStack, customDataType, customDataOf.invoke(null, compoundTag));
        } catch (Exception | LinkageError e) {
            try {
                LegacyPublicMethods.method(itemStack.getClass(), "setTag",
                    Class.forName("net.minecraft.nbt.CompoundTag")).invoke(itemStack, compoundTag);
            } catch (Exception e2) {
                // Give up
            }
        }
    }

    /** {@code stack.getOrCreateTag()}: the stack's own stored tag, made empty when it has none. */
    public static Object getOrCreateTag(Object itemStack) {
        Object tag = getTag(itemStack);
        if (tag != null) return tag;
        try {
            ensureHost(itemStack);
            synchronized (itemStack) {
                // Data this bridge cannot reach stays: replacing it with an empty tag would lose it.
                if ((boolean) hasComponent.invoke(itemStack, customDataType)) return null;
                // CustomData.of stores a copy, so the stored tag is read back; a write to the new one would be lost.
                Object own = customDataOf.invoke(null, compoundTagClass.getDeclaredConstructor().newInstance());
                setComponent.invoke(itemStack, customDataType, own);
                remember(itemStack, own);
                return storedTag(own);
            }
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** {@code stack.getTagElement(key)}: the compound stored under {@code key}, or null. */
    public static Object getTagElement(Object itemStack, String key) {
        Object tag = getTag(itemStack);
        Object element = tag == null ? null : tagCall(tag, "get", key);
        return isCompound(element) ? element : null;
    }

    /** {@code stack.getOrCreateTagElement(key)}: the compound under {@code key}, added to the stored tag when missing. */
    public static Object getOrCreateTagElement(Object itemStack, String key) {
        Object tag = getOrCreateTag(itemStack);
        if (tag == null) return null;
        Object element = tagCall(tag, "get", key);
        if (isCompound(element)) return element;
        try {
            Object created = tag.getClass().getDeclaredConstructor().newInstance();
            tagCall(tag, "put", key, created);
            return created;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** {@code stack.addTagElement(key, value)}: put into the stored tag, made when missing. */
    public static void addTagElement(Object itemStack, String key, Object value) {
        Object tag = getOrCreateTag(itemStack);
        if (tag != null) tagCall(tag, "put", key, value);
    }

    /** {@code stack.removeTagKey(key)}: removed from the stored tag, and a tag left empty goes, as it did before 1.20.5. */
    public static void removeTagKey(Object itemStack, String key) {
        Object tag = getTag(itemStack);
        if (tag == null) return;
        tagCall(tag, "remove", key);
        if (Boolean.TRUE.equals(tagCall(tag, "isEmpty"))) {
            try {
                ensureHost(itemStack);
                removeComponent.invoke(itemStack, customDataType);
            } catch (Exception | LinkageError e) {
                // The empty tag stays, which reads the same as none.
            }
        }
    }

    private static boolean isCompound(Object tag) {
        return tag != null && tag.getClass().getName().equals("net.minecraft.nbt.CompoundTag");
    }

    /** A CompoundTag method by name and arity; the tag type a parameter names differs between hosts. */
    private static Object tagCall(Object tag, String name, Object... args) {
        for (Method method : LegacyPublicMethods.methods(tag.getClass())) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            if (args.length > 0 && types[0] != String.class) continue;
            try {
                return method.invoke(tag, args);
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
