/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;

import java.util.List;

/** Client half of {@link Test41LegacyRenderLayersAndColors}: host tints ask the old color. */
public final class Test43ClientHostTints implements Test {

    @Override
    public String description() {
        return "the host block colors ask the old block color provider";
    }

    @Override
    public TestResult run() {
        if (Test41LegacyRenderLayersAndColors.tintedLeaves == null) {
            return TestResult.fail("the common case did not register its block");
        }
        // One tint source per tint index asks the old color. The 26.x members are read by
        // reflection, because this mod compiles against 1.20.1.
        Object colors = MinecraftClient.getInstance().getBlockColors();
        BlockState state = Test41LegacyRenderLayersAndColors.tintedLeaves.getDefaultState();
        try {
            List<?> sources = (List<?>) colors.getClass().getMethod("getTintSources", state.getClass())
                    .invoke(colors, state);
            if (sources.size() < 3) return TestResult.fail("the host has " + sources.size() + " tint sources");
            Object source = sources.get(2);
            int color = (Integer) source.getClass().getMethod("color", state.getClass()).invoke(source, state);
            int expected = 0xFF000000 | (Test41LegacyRenderLayersAndColors.BLOCK_TINT + 2);
            return color == expected ? TestResult.success()
                    : TestResult.fail("tint source 2 answered " + Integer.toHexString(color));
        } catch (ReflectiveOperationException e) {
            return TestResult.fail("the host block colors are not the 26.x shape: " + e);
        }
    }
}
