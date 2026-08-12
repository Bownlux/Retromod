/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Regression test for the {@code INVOKESPECIAL}-on-non-direct-supertype bug
 * that crashed ModMenu when a redirected {@code super.keyPressed} call
 * landed on an interface that wasn't in the direct superinterface list of
 * the calling class.
 *
 * <p>The {@link SuperCallScreen} below extends {@link Screen} and overrides
 * {@code keyPressed}, calling {@code super.keyPressed(...)}. The {@code super}
 * call compiles to an {@code INVOKESPECIAL} on {@code Screen.keyPressed}.
 * Source is built against MC 1.20.1 where the signature is
 * {@code keyPressed(int, int, int)}. After Retromod translates forward to
 * MC 26.1+, the call gets remapped to the new {@code keyPressed(KeyEvent)}
 * signature on whatever class/interface owns it post-rewrite. If the
 * remapped owner isn't a direct supertype of {@code SuperCallScreen}, the
 * JVM verifier rejects the class with:
 *
 * <pre>
 *   Bad invokespecial instruction:
 *   interface method to invoke is not in a direct superinterface
 * </pre>
 *
 * <p>Retromod preserves the direct-superclass {@code INVOKESPECIAL}, constructs
 * the new key event from the old primitive arguments, and updates the call descriptor. The test
 * invokes the old entry point because class loading alone does not resolve the stale method call.
 */
public class Test05SuperKeyPressed implements Test {

    @Override
    public String description() {
        return "super.keyPressed INVOKESPECIAL";
    }

    @Override
    public TestResult run() {
        try {
            // An unused key avoids closing the screen or triggering navigation. Executing this
            // method forces resolution of the transformed super call and its KeyEvent constructor.
            new SuperCallScreen().keyPressed(-1, 0, 0);
            return TestResult.success();
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Deliberately a separate class so its old override and super call are independently
     * transformed before {@link #run()} executes them.
     */
    private static class SuperCallScreen extends Screen {
        protected SuperCallScreen() {
            super(Text.literal("retromod-test-super"));
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // This call compiles to INVOKESPECIAL Screen.keyPressed - exactly
            // the case the Retromod transformer fixup targets.
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
