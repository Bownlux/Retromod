/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** FlyingMob, which 1.21.6 removed: a mob that flies through air, water and lava, and never falls or climbs. */
public abstract class LegacyFlyingMob extends Mob {

    // Each translated mod carries its own copy under its own package, so the check goes by name.
    private static final String NAME = "com.retromod.legacyapi.minecraft.world.entity.LegacyFlyingMob";

    protected LegacyFlyingMob(EntityType<? extends LegacyFlyingMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    /** Flies at the speed the ghast, a former subclass, still uses. */
    @Override
    public void travel(Vec3 input) {
        travelFlying(input, 0.02F);
    }

    @Override
    public boolean onClimbable() {
        return false;
    }

    /** {@code instanceof FlyingMob}: the ghast and the phantom, which extended it, and every mod's flying mob. */
    public static boolean isFlyingMob(Object value) {
        if (value instanceof Ghast || value instanceof Phantom) return true;
        for (Class<?> type = value == null ? null : value.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals(NAME) || name.endsWith("." + NAME)) return true;
        }
        return false;
    }
}
