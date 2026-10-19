/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * #349: 26.3 removed {@code LivingEntity.swinging} ({@code handSwinging} here), which a physics
 * mod reads to react to the arm swing. A read asks {@code isSwinging()} instead, also through a
 * player, which javac names as the owner. Reads on a null receiver prove linkage only: the bridge
 * links, then fails on the missing receiver.
 */
public final class Test30LegacySwinging implements Test {

    @Override
    public String description() {
        return "#349 LivingEntity.swinging reads after 26.3 removed the field";
    }

    @Override
    public TestResult run() {
        String failure = linkage("LivingEntity.swinging", () -> {
            boolean swinging = ((LivingEntity) null).handSwinging;
        });
        if (failure == null) failure = linkage("PlayerEntity.swinging", () -> {
            boolean swinging = ((PlayerEntity) null).handSwinging;
        });
        return failure == null ? TestResult.success() : TestResult.fail(failure);
    }

    /** Only a linkage error counts: anything thrown after the read links is the null receiver. */
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
}
