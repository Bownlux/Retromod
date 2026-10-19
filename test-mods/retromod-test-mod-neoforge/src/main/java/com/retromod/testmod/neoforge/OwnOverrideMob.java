/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/**
 * #346 follow-up: a mob that overrides {@code swing(hand, send)}, which 26.3 removed, and calls it
 * on itself or through a {@code LivingEntity} reference. The call must keep running the override,
 * where Retromod bridges the removed host method for entities that do not override it.
 *
 * <p>The call on the mob itself is made on a null mob, which never needs a level: if the call
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
    public void swing(InteractionHand hand, boolean sendToSwingingEntity) {
        swings++;
        lastSwingSent = sendToSwingingEntity;
    }

    /**
     * Whether swings through a {@code LivingEntity} reference still reach the override. The old
     * {@code swing(hand)} reached it as {@code swing(hand, false)}. The mob is allocated without
     * its constructor, which needs a level; the override touches nothing else.
     */
    static boolean livingEntityCallsReachOwnOverride() throws Throwable {
        java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        LivingEntity mob = (LivingEntity) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, OwnOverrideMob.class);
        swings = 0;
        mob.swing(InteractionHand.MAIN_HAND, true);
        if (swings != 1 || !lastSwingSent) return false;
        mob.swing(InteractionHand.MAIN_HAND);
        return swings == 2 && !lastSwingSent;
    }

    /** Whether the mob's swing call goes to its own override. */
    static boolean swingReachesOwnOverride() {
        try {
            ((OwnOverrideMob) null).swing(InteractionHand.MAIN_HAND, true);
            return false;
        } catch (NullPointerException expected) {
            StackTraceElement thrower = expected.getStackTrace()[0];
            return thrower.getClassName().equals(OwnOverrideMob.class.getName());
        }
    }
}
