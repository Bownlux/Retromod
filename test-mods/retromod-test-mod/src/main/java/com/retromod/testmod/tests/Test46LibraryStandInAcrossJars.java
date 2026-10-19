/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testlib.RetromodTestLib;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.item.ToolMaterial;
import net.minecraft.recipe.Ingredient;

/**
 * #362: the companion library jar (retromod-test-lib) takes ToolMaterial in its API. From 1.21.2
 * Retromod rebuilds that interface inside every translated jar, so this call only links when the
 * test mod reuses the library's copy instead of its own.
 */
public final class Test46LibraryStandInAcrossJars implements Test {

    @Override
    public String description() {
        return "a library's API taking the removed ToolMaterial links across jars";
    }

    @Override
    public TestResult run() {
        float speed = LibraryCall.speed();
        return speed == 6.0F ? TestResult.success()
                : TestResult.fail("the library read a mining speed of " + speed);
    }

    /** Holds the call, so a type a host lacks fails this test instead of loading the suite. */
    private static final class LibraryCall {
        static float speed() {
            return RetromodTestLib.speed(new LibraryMaterial());
        }
    }

    /** An iron-like material the test mod hands to its companion library. */
    private static final class LibraryMaterial implements ToolMaterial {
        @Override public int getDurability() { return 250; }
        @Override public float getMiningSpeedMultiplier() { return 6.0F; }
        @Override public float getAttackDamage() { return 2.0F; }
        @Override public int getMiningLevel() { return 2; }
        @Override public int getEnchantability() { return 14; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.EMPTY; }
    }
}
