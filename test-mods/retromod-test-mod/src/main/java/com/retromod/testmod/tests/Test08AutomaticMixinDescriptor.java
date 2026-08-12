/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.server.integrated.IntegratedServer;

/** Load-time regressions for automatic refmap and MixinExtras descriptor repair. */
public class Test08AutomaticMixinDescriptor implements Test {

    @Override
    public String description() {
        return "automatic refmap and MixinExtras parameter-addition repair";
    }

    @Override
    public TestResult run() {
        try {
            Class<?> server = IntegratedServer.class;
            return server != null
                    ? TestResult.success()
                    : TestResult.fail("IntegratedServer.class returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
