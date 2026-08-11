/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.gui.screen.option.ChatOptionsScreen;

/** Load-time regression for rebasing a mixin from the removed simple options screen. */
public class Test11ChatOptionsSuperclass implements Test {

    @Override
    public String description() {
        return "chat options mixin superclass repair";
    }

    @Override
    public TestResult run() {
        try {
            Class<?> chatOptions = ChatOptionsScreen.class;
            return chatOptions != null
                    ? TestResult.success()
                    : TestResult.fail("ChatOptionsScreen.class returned null");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
