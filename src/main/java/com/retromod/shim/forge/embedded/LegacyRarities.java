/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;

/**
 * Forge's {@code Rarity.create(name, color)} on NeoForge, where a mod adds a rarity through an
 * enum extension declared before the game starts. See
 * {@link com.retromod.shim.forge.LegacyRarityBridge}, which declares each constant call it finds
 * under a name ending in {@code ":" + name}. This finds that constant.
 *
 * <p>A rarity built from a name only known at run time was never declared, and Forge 26.x cannot add
 * one at all. Such a call gets the vanilla rarity of the same color, or {@code COMMON}, so the item
 * still registers with a plain name color.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyRarities {

    private static final String RARITY = "net.minecraft.world.item.Rarity";

    private LegacyRarities() {}

    public static Object find(String name, Object color) {
        try {
            Class<?> rarity = Class.forName(RARITY, true, LegacyRarities.class.getClassLoader());
            Object[] values = rarity.getEnumConstants();
            Method serializedName = rarity.getMethod("getSerializedName");
            for (Object value : values) {
                if (String.valueOf(serializedName.invoke(value)).endsWith(":" + name)) return value;
            }
            Object sameColor = sameColor(rarity, values, color);
            System.getLogger("Retromod").log(System.Logger.Level.WARNING,
                    "Rarity " + name + " cannot be added on this loader; using " + sameColor + ", the rarity of its color");
            return sameColor;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Retromod could not find the rarity " + name, e);
        }
    }

    private static Object sameColor(Class<?> rarity, Object[] values, Object color) {
        try {
            Method colorOf = rarity.getMethod("color");
            for (Object value : values) {
                if (colorOf.invoke(value) == color) return value;
            }
        } catch (ReflectiveOperationException ignored) {
            // A host without color() takes the first rarity.
        }
        return values[0];
    }
}
