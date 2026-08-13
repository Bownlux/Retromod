/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.world.World;

/** Executes the inherited Entity#getWorld call shape reported by ENGRAM (#179). */
public final class Test15InheritedEntityWorld implements Test {

    @Override
    public String description() {
        return "inherited Entity world alias";
    }

    @Override
    public TestResult run() {
        try {
            World world = MinecraftClient.getInstance().world;
            if (world == null) {
                return TestResult.fail("client world is unavailable");
            }
            LegacyItemEntity entity = new LegacyItemEntity(world);
            return entity.legacyWorld() == world
                    ? TestResult.success()
                    : TestResult.fail("inherited world call returned a different world");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Keeps the invocation owner on a mod subclass, matching ENGRAM's entity bytecode. */
    private static final class LegacyItemEntity extends ItemEntity {
        private LegacyItemEntity(World world) {
            super(EntityType.ITEM, world);
        }

        private World legacyWorld() {
            return getWorld();
        }
    }
}
