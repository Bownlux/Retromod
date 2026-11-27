/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.FuelValues;

/** The world's fuel values an old furnace override asks its inherited burn time with, on hosts from 1.21.2 to 26.2. */
public final class LegacyFurnaceFuels {

    private LegacyFurnaceFuels() {}

    /** The fuel values of the furnace's level. */
    public static FuelValues fuelValues(Object level) {
        return ((Level) level).fuelValues();
    }
}
