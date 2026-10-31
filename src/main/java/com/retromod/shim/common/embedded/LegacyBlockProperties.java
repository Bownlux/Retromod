/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Function;

/** Keeps block properties an old mod copied from a vanilla block usable for the mod's own block. */
public final class LegacyBlockProperties {

    private LegacyBlockProperties() {}

    /**
     * Guards the copied map color. 1.21 gave some vanilla blocks a color that reads one of their
     * state properties, the barrier's {@code waterlogged} among them, so a mod block built from
     * {@code copy(Blocks.BARRIER)} without that property failed while its states were created.
     */
    public static Object guardMapColor(Object properties) {
        try {
            Field field = mapColorField(properties.getClass());
            if (field == null) return properties;
            @SuppressWarnings("unchecked")
            Function<Object, Object> copied = (Function<Object, Object>) field.get(properties);
            if (copied == null) return properties;
            Object none = Class.forName("net.minecraft.world.level.material.MapColor", true,
                    properties.getClass().getClassLoader()).getField("NONE").get(null);
            // A lambda, not a nested class: this class is embedded into each mod on its own.
            Function<Object, Object> guarded = state -> {
                try {
                    return copied.apply(state);
                } catch (IllegalArgumentException missingProperty) {
                    return none;
                }
            };
            field.set(properties, guarded);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The plain copy is what the mod asked for; only the guard is lost.
        }
        return properties;
    }

    /** The properties' {@code Function<BlockState, MapColor>} field. */
    private static Field mapColorField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != Function.class) continue;
                if (!field.getName().equals("mapColor")) continue;
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}
