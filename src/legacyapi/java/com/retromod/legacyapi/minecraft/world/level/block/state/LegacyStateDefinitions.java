/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block.state;

import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/** A block state builder that accepts a property its vanilla base class added since the mod was built. */
public final class LegacyStateDefinitions {

    private LegacyStateDefinitions() {}

    /** {@code builder.add(properties)}, skipping one the builder already holds, as WATERLOGGED on 1.21.2's MultifaceBlock. */
    public static StateDefinition.Builder<?, ?> add(StateDefinition.Builder<?, ?> builder, Property<?>... properties) {
        for (Property<?> property : properties) {
            try {
                builder.add(property);
            } catch (IllegalArgumentException duplicate) {
                // Only the duplicate refusal is expected here; any other problem with the property still fails.
                if (!String.valueOf(duplicate.getMessage()).contains("has duplicate property")) throw duplicate;
                // Only the very property the vanilla base added is skipped. A mod's own property that reuses
                // the name, or a builder whose map cannot be read, still fails as before.
                if (heldProperty(builder, property.getName()) != property) throw duplicate;
            }
        }
        return builder;
    }

    /** The property of that name the builder already holds, read from its private map, or null when unreadable. */
    private static Object heldProperty(Object builder, String name) {
        Field map = null;
        for (Field field : builder.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) continue;
            // The host names it properties; any single map field is accepted, more than one is ambiguous.
            if (field.getName().equals("properties")) {
                map = field;
                break;
            }
            if (map != null) return null;
            map = field;
        }
        if (map == null) return null;
        try {
            map.setAccessible(true);
            return map.get(builder) instanceof Map<?, ?> properties ? properties.get(name) : null;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }
}
