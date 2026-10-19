/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * #346 follow-up: a mob that overrides the old five-argument {@code finalizeSpawn} and
 * {@code swing(hand, send)}, as an MCreator mob with an "on initial spawn" procedure does, and calls
 * them on itself or through a {@code Mob} or {@code LivingEntity} reference. Those calls must keep
 * running the overrides on newer hosts, where Retromod bridges the removed host methods for mobs
 * that do not override them.
 *
 * <p>The calls on the mob itself are made on a null mob, which never needs a level: if the call
 * reaches the override, the null receiver fails right here; if a bridge captured it, it fails
 * inside the bridge instead.
 */
public class OwnOverrideMob extends PathfinderMob {

    /** Swings that reached the override, and whether the last one was sent to the player. */
    static int swings;
    static boolean lastSwingSent;

    protected OwnOverrideMob(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
            MobSpawnType reason, SpawnGroupData spawnData, CompoundTag tag) {
        return spawnData;
    }

    @Override
    public void swing(InteractionHand hand, boolean sendToSwingingEntity) {
        swings++;
        lastSwingSent = sendToSwingingEntity;
    }

    /** Whether the offspring's spawn call goes to the mob's own override. */
    static boolean breedReachesOwnOverride() {
        return reachesOwnMethod(() -> ((OwnOverrideMob) null).finalizeSpawn(null, null,
                MobSpawnType.BREEDING, null, null));
    }

    /** Whether the mob's swing call goes to its own override. */
    static boolean swingReachesOwnOverride() {
        return reachesOwnMethod(() -> ((OwnOverrideMob) null).swing(InteractionHand.MAIN_HAND, true));
    }

    /**
     * Whether calls that name {@code Mob} or {@code LivingEntity}, as MCreator's spawn procedure
     * makes them, still reach this mob's overrides. The old {@code swing(hand)} reached the
     * two-argument override as {@code swing(hand, false)}. The mob is allocated without its
     * constructor, which needs a level; the overrides touch nothing else.
     */
    static boolean hostTypedCallsReachOwnOverrides() throws Throwable {
        java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        OwnOverrideMob mob = (OwnOverrideMob) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, OwnOverrideMob.class);
        SpawnGroupData marker = new SpawnGroupData() {};
        Mob asMob = mob;
        if (asMob.finalizeSpawn(null, null, MobSpawnType.COMMAND, marker, null) != marker) return false;
        LivingEntity asLiving = mob;
        swings = 0;
        asLiving.swing(InteractionHand.MAIN_HAND, true);
        if (swings != 1 || !lastSwingSent) return false;
        asLiving.swing(InteractionHand.MAIN_HAND);
        return swings == 2 && !lastSwingSent;
    }

    private static boolean reachesOwnMethod(Runnable call) {
        try {
            call.run();
            return false;
        } catch (NullPointerException expected) {
            StackTraceElement thrower = expected.getStackTrace()[0];
            return thrower.getClassName().equals(OwnOverrideMob.class.getName());
        }
    }
}
