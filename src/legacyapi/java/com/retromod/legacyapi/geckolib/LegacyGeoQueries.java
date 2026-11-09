/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.geckolib;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;

/** The saddle and charge checks GeckoLib 4's Molang queries make through interfaces Minecraft removed. */
public final class LegacyGeoQueries {

    private LegacyGeoQueries() {}

    /** {@code instanceof Saddleable}: every mob can carry a saddle in the equipment that replaced it. */
    public static boolean isSaddleable(Object entity) {
        return entity instanceof Mob;
    }

    /** {@code Saddleable.isSaddled()}. */
    public static boolean isSaddled(Object entity) {
        return entity instanceof Mob mob && mob.isSaddled();
    }

    /** {@code instanceof PowerableMob}: the creeper and the wither were the two that implemented it. */
    public static boolean isPowerable(Object entity) {
        return entity instanceof Creeper || entity instanceof WitherBoss;
    }

    /** {@code PowerableMob.isPowered()}. */
    public static boolean isPowered(Object entity) {
        if (entity instanceof Creeper creeper) return creeper.isPowered();
        return entity instanceof WitherBoss wither && wither.isPowered();
    }
}
