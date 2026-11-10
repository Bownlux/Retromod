/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level;

import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.dimension.DimensionType;

/** The LevelTimeAccess members 1.21.11 removed, read from the environment attributes that took over the sky. */
public final class LegacyLevelTime {

    private LegacyLevelTime() {}

    /** {@code getMoonPhase()}: the phase the host's sky shows, from 0 (full moon) to 7. */
    public static int getMoonPhase(Object level) {
        if (!(level instanceof LevelReader reader)) return 0;
        MoonPhase phase = reader.environmentAttributes().getDimensionValue(EnvironmentAttributes.MOON_PHASE);
        return phase == null ? 0 : phase.index();
    }

    /** {@code getMoonBrightness()}: the brightness 1.20.1 gave each phase. */
    public static float getMoonBrightness(Object level) {
        return DimensionType.MOON_BRIGHTNESS_PER_PHASE[getMoonPhase(level)];
    }

    /** {@code getTimeOfDay(partialTick)}: the sun's turn through the day, 0 at noon; 1.20.1 ignored the partial tick too. */
    public static float getTimeOfDay(Object level, float partialTick) {
        if (!(level instanceof LevelReader reader)) return 0.0F;
        // The host draws the sun at this angle in degrees, the old fraction of a turn times 360.
        Float angle = reader.environmentAttributes().getDimensionValue(EnvironmentAttributes.SUN_ANGLE);
        return angle == null ? 0.0F : angle / 360.0F;
    }

    /** {@code dayTime()}: the overworld's clock, which every dimension reported in 1.20.1. */
    public static long dayTime(Object level) {
        return level instanceof Level world ? world.getOverworldClockTime() : 0L;
    }
}
