/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.sound.SoundSystem;

/** Load-time regression for repairing a partially captured exact Mixin target. */
public class Test10ExactTargetPrefixCapture implements Test {

    @Override
    public String description() {
        return "exact mixin target prefix-capture repair";
    }

    @Override
    public TestResult run() {
        try {
            Class<?> soundSystem = SoundSystem.class;
            return soundSystem != null
                    ? TestResult.success()
                    : TestResult.fail("SoundSystem.class returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
