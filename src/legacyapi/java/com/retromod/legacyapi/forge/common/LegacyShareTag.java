/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Forge's {@code Item.getShareTag(stack)}, which went with stack NBT: the item's own override, else the stack's custom data. */
public final class LegacyShareTag {

    // Each item class's override, looked up once; a class without one maps to NONE.
    private static final Method NONE = noneMarker();
    private static final ClassValue<Method> OVERRIDES = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            Method found = override(type);
            return found == null ? NONE : found;
        }
    };

    private LegacyShareTag() {}

    /** {@code item.getShareTag(stack)}, which runs a mod item's own override as the old call did. */
    public static CompoundTag shareTag(Object item, ItemStack stack) {
        Method override = OVERRIDES.get(item.getClass());
        if (override == NONE) return hostShareTag(item, stack);
        try {
            return (CompoundTag) override.invoke(item, stack);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Retromod could not run " + override, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not run " + override, e);
        }
    }

    /** Forge's default, also an override's {@code super.getShareTag(stack)}: a copy of the stack's custom data, or null. */
    public static CompoundTag hostShareTag(Object item, ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    /** A mod item's own {@code getShareTag(ItemStack)}; Minecraft and the loader declare none any more. */
    private static Method override(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (current.getName().startsWith("net.minecraft.")) return null;
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("getShareTag") || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != ItemStack.class || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isPublic(current.getModifiers())) {
                    method.setAccessible(true);
                }
                return method;
            }
        }
        return null;
    }

    private static Method noneMarker() {
        try {
            return Object.class.getMethod("toString");
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
