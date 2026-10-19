/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.TemptGoal;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/**
 * #346: what a 1.20.1 mob calls for its goals, food, offspring and milking, against the members
 * later hosts removed. Cake Cow crashed as soon as it was spawned: its tempt goal took an
 * {@code Ingredient}, and after that its attributes lacked the tempt range the newer goal reads.
 *
 * <p>Calls on a null mob prove linkage only: the bridge links, then forwards to the missing
 * receiver. The attribute check builds the attributes and looks for the tempt range itself.
 */
public final class Test22LegacyMobApi implements Test {

    @Override
    public String description() {
        return "#346 mob goals, food, offspring and milking";
    }

    @Override
    public TestResult run() {
        String failure = linkage("TemptGoal with an Ingredient",
                () -> new TemptGoal(null, 1.0, Ingredient.ofItems(Items.WHEAT), false));
        if (failure == null) failure = linkage("Ingredient.ofStacks",
                () -> Ingredient.ofStacks(new ItemStack(Items.WHEAT)));
        if (failure == null) failure = linkage("EntityType.create(World)",
                () -> EntityType.COW.create((World) null));
        if (failure == null) failure = linkage("MobEntity.initialize with an NBT compound",
                () -> ((MobEntity) null).initialize(null, null, SpawnReason.BREEDING, null, null));
        if (failure == null) failure = linkage("LivingEntity.swingHand(hand, send)",
                () -> ((LivingEntity) null).swingHand(Hand.MAIN_HAND, true));
        if (failure == null) failure = temptRange();
        return failure == null ? TestResult.success() : TestResult.fail(failure);
    }

    /** Only a linkage error counts: anything thrown after the call links is the null receiver. */
    private static String linkage(String label, Runnable probe) {
        try {
            probe.run();
            return null;
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            return label + " did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable linkedThenRefused) {
            return null;
        }
    }

    /**
     * Attributes built from each host factory must carry the tempt range a 1.21.2+ goal reads. On
     * a host before that, or one still named by intermediary, there is no field of that name to
     * look for.
     */
    private static String temptRange() {
        try {
            Field range;
            try {
                range = EntityAttributes.class.getField("TEMPT_RANGE");
            } catch (NoSuchFieldException noAttribute) {
                return null;
            }
            Object temptRange = range.get(null);
            Object[] built = {
                    MobEntity.createMobAttributes().build(),
                    HostileEntity.createHostileAttributes().build(),
                    LivingEntity.createLivingAttributes().build()};
            for (Object attributes : built) {
                boolean present = (boolean) attributes.getClass().getMethod("hasAttribute", range.getType())
                        .invoke(attributes, temptRange);
                if (!present) return attributes.getClass().getSimpleName() + " lacks the tempt range";
            }
            return null;
        } catch (Throwable t) {
            return "tempt range check failed: " + t;
        }
    }
}
