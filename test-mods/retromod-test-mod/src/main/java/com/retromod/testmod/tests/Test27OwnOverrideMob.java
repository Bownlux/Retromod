/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Hand;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/**
 * #346 follow-up: a mob that overrides the old five-argument {@code initialize}
 * ({@code finalizeSpawn}) and {@code swingHand(hand, send)}, as an MCreator mob with an "on initial
 * spawn" procedure does, and calls them on itself or through a {@code MobEntity} or
 * {@code LivingEntity} reference. Newer hosts removed both, and Retromod bridges the calls for mobs
 * that do not override them, but these calls must keep running the overrides.
 *
 * <p>The calls on the mob itself are made on a null mob, which never needs a world: if the call
 * reaches the override, the null receiver fails in this class; if a bridge captured it, it fails
 * inside the bridge instead. The calls through a host type need a real mob, which is allocated
 * without its constructor; the overrides touch nothing else.
 */
public final class Test27OwnOverrideMob implements Test {

    @Override
    public String description() {
        return "#346 a mob's own spawn and swing overrides still run";
    }

    @Override
    public TestResult run() {
        if (!reachesOwnMethod(() -> ((OwnOverrideMob) null).initialize(null, null, SpawnReason.BREEDING, null, null))) {
            return TestResult.fail("the offspring's spawn call skipped the mob's own override");
        }
        if (!reachesOwnMethod(() -> ((OwnOverrideMob) null).swingHand(Hand.MAIN_HAND, true))) {
            return TestResult.fail("the swing call skipped the mob's own override");
        }
        try {
            return hostTypedCallsReachOverrides(allocate(OwnOverrideMob.class));
        } catch (Exception | LinkageError e) {
            return TestResult.fail("a call through a host type failed: " + e);
        }
    }

    /**
     * MCreator's spawn procedure calls {@code initialize} on a {@code MobEntity} it cast to. The
     * old {@code swingHand(hand)} reached the two-argument override as {@code swingHand(hand, false)}.
     */
    private static TestResult hostTypedCallsReachOverrides(OwnOverrideMob mob) {
        EntityData marker = new EntityData() {};
        MobEntity asMob = mob;
        if (asMob.initialize(null, null, SpawnReason.COMMAND, marker, null) != marker) {
            return TestResult.fail("a spawn call through MobEntity skipped the mob's own override");
        }
        LivingEntity asLiving = mob;
        OwnOverrideMob.swings = 0;
        asLiving.swingHand(Hand.MAIN_HAND, true);
        if (OwnOverrideMob.swings != 1 || !OwnOverrideMob.lastSwingSent) {
            return TestResult.fail("a swing through LivingEntity skipped the mob's own override");
        }
        asLiving.swingHand(Hand.MAIN_HAND);
        if (OwnOverrideMob.swings != 2 || OwnOverrideMob.lastSwingSent) {
            return TestResult.fail("swingHand(hand) did not reach the override as swingHand(hand, false)");
        }
        return TestResult.success();
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    private static boolean reachesOwnMethod(Runnable call) {
        try {
            call.run();
            return false;
        } catch (NullPointerException expected) {
            return expected.getStackTrace()[0].getClassName().equals(Test27OwnOverrideMob.class.getName());
        }
    }

    /** The overrides an MCreator mob with spawn and swing procedures declares. */
    public static class OwnOverrideMob extends PathAwareEntity {

        /** Swings that reached the override, and whether the last one was sent to the player. */
        static int swings;
        static boolean lastSwingSent;

        protected OwnOverrideMob(EntityType<? extends PathAwareEntity> type, World world) {
            super(type, world);
        }

        @Override
        public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason reason,
                EntityData entityData, NbtCompound entityNbt) {
            return entityData;
        }

        @Override
        public void swingHand(Hand hand, boolean fromServerPlayer) {
            swings++;
            lastSwingSent = fromServerPlayer;
        }
    }
}
