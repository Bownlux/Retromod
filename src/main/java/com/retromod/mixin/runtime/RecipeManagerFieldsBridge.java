/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import java.lang.reflect.Field;

/** Reads {@code RecipeManager.byType}, which moved into the {@code RecipeMap} the manager has kept since 1.21.2. */
public final class RecipeManagerFieldsBridge {
    private RecipeManagerFieldsBridge() {}

    /** The recipe map's own multimap of recipes by type, the same shape the old field had. */
    public static Object byType(Object manager) {
        return read(read(manager, "recipes"), "byType");
    }

    private static Object read(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read " + name + " from "
                    + owner.getClass().getName() + " on this Minecraft version", e);
        }
    }
}
