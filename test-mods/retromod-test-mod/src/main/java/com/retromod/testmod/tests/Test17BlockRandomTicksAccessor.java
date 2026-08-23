/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import com.retromod.testmod.mixin.LegacyBlockRandomTicksAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.lang.reflect.Method;

/** In-game regression for the legacy Block random-tick setter and state cache. */
public class Test17BlockRandomTicksAccessor implements Test {

    @Override
    public String description() {
        return "block random-tick accessor owner move and cache refresh";
    }

    @Override
    public TestResult run() {
        Block block = Blocks.ICE;
        LegacyBlockRandomTicksAccessor accessor = (LegacyBlockRandomTicksAccessor) block;
        try {
            boolean original = readsRandomTicks(block);
            boolean changed = !original;

            try {
                accessor.setRandomTicks(changed);
                if (readsRandomTicks(block) != changed) {
                    return TestResult.fail("cached block state did not follow the accessor value");
                }
            } finally {
                accessor.setRandomTicks(original);
            }
            return readsRandomTicks(block) == original
                    ? TestResult.success()
                    : TestResult.fail("random-tick state did not restore after the test");
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean readsRandomTicks(Block block) throws ReflectiveOperationException {
        Object state = block.getDefaultState();
        for (String methodName : new String[]{"isRandomlyTicking", "hasRandomTicks"}) {
            try {
                Method method = state.getClass().getMethod(methodName);
                return (boolean) method.invoke(state);
            } catch (NoSuchMethodException ignored) {
                // Try the name used by the other namespace generation.
            }
        }
        throw new NoSuchMethodException("block state random-tick query");
    }
}
