/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.ai.navigation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

import java.lang.reflect.Field;

/** The navigation's own liquid check, which moved onto the entity it steers. */
public final class LegacyPathNavigations {

    private static volatile Field mobField;

    private LegacyPathNavigations() {}

    /** {@code navigation.isInLiquid()}: whether the mob it steers is in water, a bubble column or lava. */
    public static boolean isInLiquid(PathNavigation navigation) {
        Mob mob = mob(navigation);
        return mob != null && mob.isInLiquid();
    }

    private static Mob mob(PathNavigation navigation) {
        try {
            // The mob is a protected field, so it is found once by its type and read through reflection.
            Field field = mobField;
            if (field == null) {
                for (Field candidate : PathNavigation.class.getDeclaredFields()) {
                    if (candidate.getType() == Mob.class) field = candidate;
                }
                if (field == null) return null;
                field.setAccessible(true);
                mobField = field;
            }
            return (Mob) field.get(navigation);
        } catch (ReflectiveOperationException | RuntimeException closed) {
            return null;
        }
    }
}
