/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/**
 * #347 follow-up: a mob that overrides the old {@code damage(source, amount)} and ends with
 * {@code super.damage(source, amount)}, as an MCreator mob with a "when hurt" procedure does.
 * 1.21.2 split that method, so a call through a {@code LivingEntity} reference must still reach the
 * override, and the override's super call, which names the mob's direct superclass, must still
 * link to the host's damage. Fabric hosts from 1.21.2 to 1.21.11 run this in intermediary names,
 * and 26.x hosts in Mojang names.
 *
 * <p>The mob is allocated without its constructor, so the host's damage fails on the missing
 * world once the super call links. Only a linkage error, or an override that did not run exactly
 * once, fails the test.
 */
public final class Test29OwnDamageOverride implements Test {

    @Override
    public String description() {
        return "#347 a mob's own damage override still runs and its super call links";
    }

    @Override
    public TestResult run() {
        OwnDamageMob mob;
        try {
            mob = allocate(OwnDamageMob.class);
        } catch (Exception e) {
            return TestResult.fail("could not allocate the mob: " + e);
        }
        LivingEntity asLiving = mob;
        String failure = reachesOverrideOnce("a damage call through LivingEntity", () -> asLiving.damage(null, 1f));
        if (failure == null) failure = reachesOverrideOnce("the mob's damage call on itself", () -> mob.damage(null, 1f));
        return failure == null ? TestResult.success() : TestResult.fail(failure);
    }

    private static String reachesOverrideOnce(String label, Runnable call) {
        OwnDamageMob.calls = 0;
        try {
            call.run();
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            return label + " did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable linkedThenRefused) {
            // The host's damage reached the missing world.
        }
        return OwnDamageMob.calls == 1 ? null
                : label + " ran the mob's own override " + OwnDamageMob.calls + " times instead of once";
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    /** The override an MCreator mob with a "when hurt" procedure declares. */
    public static class OwnDamageMob extends PathAwareEntity {

        /** Calls that reached the override. */
        static int calls;

        protected OwnDamageMob(EntityType<? extends PathAwareEntity> type, World world) {
            super(type, world);
        }

        @Override
        public boolean damage(DamageSource source, float amount) {
            calls++;
            return super.damage(source, amount);
        }
    }
}
