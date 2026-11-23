/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

/** The goal 26.3 removed that walks a water mob stranded on land back into nearby water. */
public class LegacyTryFindWaterGoal extends Goal {

    // How far around the mob it looks for water: sideways, and down to two blocks below its feet.
    private static final int REACH = 2;
    private static final int DEPTH = 2;
    private static final double SPEED = 1.0;

    private final PathfinderMob mob;

    public LegacyTryFindWaterGoal(PathfinderMob mob) {
        this.mob = mob;
    }

    /** Runs only for a mob standing on ground outside water. */
    @Override
    public boolean canUse() {
        return mob.onGround() && !mob.level().getFluidState(mob.blockPosition()).is(FluidTags.WATER);
    }

    /** Heads for the first water block found next to the mob, nearest layers first. */
    @Override
    public void start() {
        Level level = mob.level();
        BlockPos origin = mob.blockPosition();
        for (int dy = -DEPTH; dy <= 0; dy++) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (level.getFluidState(candidate).is(FluidTags.WATER)) {
                        mob.getMoveControl().setWantedPosition(candidate.getX(), candidate.getY(), candidate.getZ(), SPEED);
                        return;
                    }
                }
            }
        }
    }
}
