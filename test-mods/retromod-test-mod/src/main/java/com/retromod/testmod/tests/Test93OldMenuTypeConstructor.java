/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.class_3917;
import net.minecraft.screen.ScreenHandlerType;

/** A 1.19.2 mod's one-argument ScreenHandlerType constructor, which 1.19.3 gave a feature flag set (#412). */
public final class Test93OldMenuTypeConstructor implements Test {

    @Override
    public String description() {
        return "#412 a 1.19.2 one-argument ScreenHandlerType constructor";
    }

    @Override
    public TestResult run() {
        // Unbridged, the old constructor throws NoSuchMethodError on every host from 1.19.3.
        try {
            Object type = OldMenuType.build();
            return type instanceof ScreenHandlerType<?> ? TestResult.success() : TestResult.fail("built " + type);
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Holds the old call, so a host that cannot link it fails this test instead of loading the suite. */
    private static final class OldMenuType {
        static Object build() {
            return new class_3917(null);
        }
    }
}
