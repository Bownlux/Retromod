/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Field;
import java.util.List;

/** Old biome builder {@code getCarvers(step)} on hosts that keep one carver list, embedded per mod. */
public final class LegacyBiomeCarvers {

    // Reached by reflection, because this class is embedded into each mod and compiled without Minecraft.
    private static volatile Field carvers;

    private LegacyBiomeCarvers() {}

    /**
     * The builder's live carver list. 26.x carves in one step, so the old air and liquid steps both
     * read and write this list, and an entry a mod adds or removes is the one the biome keeps.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> carvers(Object builder, Object step) {
        try {
            return (List<Object>) carversField(builder.getClass()).get(builder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not read the carvers of biome builder "
                    + builder.getClass().getName() + " for " + step
                    + ". The mod's biome modifier cannot change this biome's carvers.", e);
        }
    }

    private static Field carversField(Class<?> type) throws NoSuchFieldException {
        Field cached = carvers;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(type)) return cached;
        // The list is declared on PlainBuilder, which both the vanilla and the loader builders extend.
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (field.getName().equals("carvers") && List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    carvers = field;
                    return field;
                }
            }
        }
        throw new NoSuchFieldException("carvers list on " + type.getName());
    }
}
