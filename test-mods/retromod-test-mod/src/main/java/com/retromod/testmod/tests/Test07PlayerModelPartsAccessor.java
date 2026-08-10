/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import com.retromod.testmod.mixin.LegacyPlayerModelPartsAccessor;

/** Load-time regression for the 1.21.11 Player to Avatar field move. */
public class Test07PlayerModelPartsAccessor implements Test {

    @Override
    public String description() {
        return "player model-parts accessor owner move";
    }

    @Override
    public TestResult run() {
        try {
            return LegacyPlayerModelPartsAccessor.retromod$getPlayerModelParts() != null
                    ? TestResult.success()
                    : TestResult.fail("accessor returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
