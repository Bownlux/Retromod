/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runtime half of {@link com.retromod.shim.fabric.Pre1_20_5ArmorMaterialBridge}: reads a
 * pre-1.20.5 armor material through its old intermediary method names.
 *
 * <p>The calls are reflective because every transformed mod gets its own relocated copy of the
 * generated {@code ArmorMaterial} interface. A material defined in one mod, such as a library, is
 * routinely passed to an armor item in another, where an interface call on the receiving mod's
 * copy fails with {@code IncompatibleClassChangeError}.
 */
public final class LegacyArmorMaterialSupport {

    private static final String VIEW_SUFFIX = "LegacyArmorMaterialView";
    private static final String ARMOR_MATERIAL_RECORD = "net.minecraft.class_1741";

    private LegacyArmorMaterialSupport() {}

    /** The holder a generated vanilla view wraps, from any mod's copy, or null for a mod material. */
    public static Object viewHolder(Object material) {
        if (material == null || !material.getClass().getName().endsWith(VIEW_SUFFIX)) return null;
        try {
            return material.getClass().getField("holder").get(material);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static int intValue(Object material, String name, Object argument) {
        return ((Number) call(material, name, argument)).intValue();
    }

    public static int intValue(Object material, String name) {
        return ((Number) call(material, name)).intValue();
    }

    public static float floatValue(Object material, String name) {
        return ((Number) call(material, name)).floatValue();
    }

    public static Object value(Object material, String name) {
        return call(material, name);
    }

    /**
     * Builds the 1.20.5 {@code ArmorMaterial} record. Generated code builds it reflectively so it
     * never names the record class, which shares its name with the old interface.
     */
    public static Object newRecord(Object anchor, java.util.Map<?, ?> defense, int enchantmentValue,
            Object equipSound, java.util.function.Supplier<?> repairIngredient, java.util.List<?> layers,
            float toughness, float knockbackResistance) {
        try {
            Class<?> record = Class.forName(ARMOR_MATERIAL_RECORD, false, anchor.getClass().getClassLoader());
            for (java.lang.reflect.Constructor<?> constructor : record.getConstructors()) {
                if (constructor.getParameterCount() == 7) {
                    return constructor.newInstance(defense, enchantmentValue, equipSound,
                            repairIngredient, layers, toughness, knockbackResistance);
                }
            }
            throw new IllegalStateException("The host ArmorMaterial record has no 7-argument constructor");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build an ArmorMaterial for "
                    + anchor.getClass().getName(), e);
        }
    }

    private static Object call(Object target, String name, Object... args) {
        if (target == null) {
            throw new IllegalStateException("Cannot read " + name + " from a missing armor material");
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try {
                // Mod materials are often package-private or anonymous classes.
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + name + " from "
                        + target.getClass().getName(), e);
            }
        }
        throw new IllegalStateException(target.getClass().getName() + " has no " + name
                + " method, so it cannot be used as a pre-1.20.5 armor material");
    }
}
