/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;

/**
 * #366: an old explosion subclass, built and finished in two steps as ProjectE's nova catalyst
 * does. 1.21.2 turned {@code Explosion} into an interface, so such a class failed to load.
 */
public final class Test55LegacyExplosionOnHost implements Test {

    private final MinecraftServer server;

    public Test55LegacyExplosionOnHost(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "an Explosion subclass explodes, then finishes";
    }

    @Override
    public TestResult run() {
        try {
            ServerWorld world = server.getOverworld();
            if (world.isClient || world.random == null) return TestResult.fail("the world reads as a client world");
            BlockPos first = new BlockPos(0, 300, 0);
            BlockPos second = first.east();
            world.setBlockState(first, Blocks.DIRT.getDefaultState());
            world.setBlockState(second, Blocks.DIRT.getDefaultState());
            TwoStepExplosion explosion = new TwoStepExplosion(world, null, 0.5, 300.5, 0.5, 3.0F);
            explosion.collectBlocksAndDamageEntities();
            if (!explosion.getAffectedBlocks().contains(first)) {
                return TestResult.fail("explode() listed " + explosion.getAffectedBlocks().size() + " blocks, without the dirt");
            }
            if (!world.getBlockState(first).isOf(Blocks.DIRT)) return TestResult.fail("explode() broke the dirt early");
            explosion.affectWorld(false);
            if (!explosion.finished) return TestResult.fail("the subclass's finalizeExplosion did not run");
            if (!world.getBlockState(first).isAir() || !world.getBlockState(second).isAir()) {
                return TestResult.fail("finalizeExplosion left the dirt");
            }
            return TestResult.success();
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Separate, so its super calls are transformed before {@link #run()} reaches them. */
    private static final class TwoStepExplosion extends Explosion {

        private boolean finished;

        TwoStepExplosion(World world, Entity entity, double x, double y, double z, float power) {
            super(world, entity, x, y, z, power, false, Explosion.DestructionType.DESTROY);
        }

        @Override
        public void affectWorld(boolean particles) {
            finished = true;
            super.affectWorld(particles);
        }
    }
}
