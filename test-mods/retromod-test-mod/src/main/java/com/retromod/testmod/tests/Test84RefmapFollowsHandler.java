/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.render.GameRenderer;

/** Load-time regression for a refmap entry that follows its retyped mixin handler. */
public class Test84RefmapFollowsHandler implements Test {

    @Override
    public String description() {
        return "refmap entry follows a retyped mixin handler";
    }

    @Override
    public TestResult run() {
        // Loading the class applies LegacyGameRendererRefmapMixin, whose require = 1 fails the load
        // when the refmap still names the 1.20.1 render(FJZ)V.
        try {
            Class<?> gameRenderer = GameRenderer.class;
            return gameRenderer != null
                    ? TestResult.success()
                    : TestResult.fail("GameRenderer.class returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
