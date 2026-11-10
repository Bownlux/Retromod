/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;

/** Load-time regression for a mixin whose target method the host renamed. */
public class Test85RenamedMixinTarget implements Test {

    @Override
    public String description() {
        return "mixin follows a renamed target method";
    }

    @Override
    public TestResult run() {
        // LegacyResolutionChangedMixin applies when MinecraftClient loads, and its require = 1 stops
        // the game there when the refmap still names resizeDisplay().
        try {
            Class<?> client = MinecraftClient.class;
            return client != null
                    ? TestResult.success()
                    : TestResult.fail("MinecraftClient.class returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
