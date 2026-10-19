/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.util.Optional;

/**
 * The old {@code new PaintingVariant(width, height)}, in pixels, for a host whose variant also
 * takes an asset id and sizes in blocks. The asset id is a placeholder: the legacy worldgen pack
 * writes the variant's file under its registry id, which names its texture as it did before.
 * Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyPaintings {
    private LegacyPaintings() {}

    public static Object create(int widthPixels, int heightPixels) {
        try {
            Class<?> variant = variantClass();
            Constructor<?> widest = null;
            for (Constructor<?> constructor : variant.getConstructors()) {
                if (widest == null || constructor.getParameterCount() > widest.getParameterCount()) widest = constructor;
            }
            Class<?>[] types = widest.getParameterTypes();
            Object[] args = new Object[types.length];
            args[0] = Math.max(1, widthPixels / 16);
            args[1] = Math.max(1, heightPixels / 16);
            for (int i = 2; i < types.length; i++) {
                args[i] = types[i] == Optional.class ? Optional.empty() : types[i].getMethod("parse", String.class)
                        .invoke(null, "minecraft:kebab");
            }
            return widest.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build an old painting variant", e);
        }
    }

    /** The 26.1 class, or the 1.21 to 1.21.11 one in the package it moved out of. */
    private static Class<?> variantClass() throws ClassNotFoundException {
        ClassLoader loader = LegacyPaintings.class.getClassLoader();
        try {
            return Class.forName("net.minecraft.world.entity.decoration.painting.PaintingVariant", true, loader);
        } catch (ClassNotFoundException before26) {
            return Class.forName("net.minecraft.world.entity.decoration.PaintingVariant", true, loader);
        }
    }
}
