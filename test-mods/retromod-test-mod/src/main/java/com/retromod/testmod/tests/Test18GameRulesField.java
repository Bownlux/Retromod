/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.world.GameRules;

/** Executes a direct read of the mob-griefing rule carried as {@code field_19388}. */
public final class Test18GameRulesField implements Test {

    @Override
    public String description() {
        return "GameRules mob-griefing field";
    }

    @Override
    public TestResult run() {
        Object rule = GameRules.DO_MOB_GRIEFING;
        return rule != null
                ? TestResult.success()
                : TestResult.fail("mob-griefing rule was null");
    }
}
