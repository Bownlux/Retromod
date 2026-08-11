/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.gui.screen.TitleScreen;

/** Load-time regression for keeping ordinary constructors out of method heuristics. */
public class Test09ConstructorHeuristicIsolation implements Test {

    private static final String PROBE_CLASS =
            "com.retromod.testmod.tests.Test09ConstructorHeuristicIsolation$ConstructorProbe";

    @Override
    public String description() {
        return "constructor isolation from fuzzy method repair";
    }

    @Override
    public TestResult run() {
        try {
            Class<?> probe = Class.forName(
                    PROBE_CLASS, false, Test09ConstructorHeuristicIsolation.class.getClassLoader());
            return probe != null
                    ? TestResult.success()
                    : TestResult.fail("constructor probe class returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static final class ConstructorProbe {
        private ConstructorProbe() {}

        static TitleScreen create() {
            return new TitleScreen();
        }
    }
}
