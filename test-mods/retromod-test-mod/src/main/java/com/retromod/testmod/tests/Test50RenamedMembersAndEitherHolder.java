/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.class_9791;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.border.WorldBorder;

import java.util.Optional;

/**
 * #271: Retromod's intermediary table carried names Mojang changed before 1.21.11, such as
 * {@code WorldBorder.getDamageSafeZone} for {@code getSafeZone}, and 26.1 removed
 * {@code EitherHolder}, which Minepathy uses to give its syringe a damage type.
 *
 * <p>The world border call on a null receiver proves linkage only. The {@code EitherHolder} check
 * runs on hosts from 1.21.11, where the class is the host's own or Retromod's stand-in, and an
 * older host has no such class to check.
 */
public final class Test50RenamedMembersAndEitherHolder implements Test {

    @Override
    public String description() {
        return "#271 a member renamed by 1.21.11, and EitherHolder after 26.1 removed it";
    }

    @Override
    public TestResult run() {
        String failure = linkage("WorldBorder.getSafeZone()", () -> ((WorldBorder) null).getSafeZone());
        if (failure == null && hostHasIdentifier()) failure = EitherHolderCalls.keyReadsBack();
        return failure == null ? TestResult.success() : TestResult.fail(failure);
    }

    /** Holds the calls, so a type a host lacks fails this test instead of loading the suite. */
    private static final class EitherHolderCalls {
        static String keyReadsBack() {
            RegistryKey<DamageType> key = RegistryKey.of(RegistryKeys.DAMAGE_TYPE,
                    new Identifier("retromod", "test_syringe"));
            Optional<RegistryKey<DamageType>> read = new class_9791<>(key).method_67385();
            return read.equals(Optional.of(key)) ? null : "EitherHolder.key() returned " + read;
        }
    }

    /** {@code Identifier} is the 1.21.11 name, so its presence marks a host this case applies to. */
    private static boolean hostHasIdentifier() {
        try {
            Class.forName("net.minecraft.resources.Identifier", false,
                    Test50RenamedMembersAndEitherHolder.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
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
}
