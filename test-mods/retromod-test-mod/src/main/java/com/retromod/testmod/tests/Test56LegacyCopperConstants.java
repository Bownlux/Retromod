/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

/** Per-state copper constants, which 26.2 folded into weathering copper collections. */
public final class Test56LegacyCopperConstants implements Test {

    @Override
    public String description() {
        return "per-state copper block and item constants";
    }

    @Override
    public TestResult run() {
        String exposed = Registries.BLOCK.getId(Blocks.EXPOSED_COPPER).getPath();
        String stairs = Registries.BLOCK.getId(Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS).getPath();
        String item = Registries.ITEM.getId(Items.WEATHERED_CUT_COPPER).getPath();
        if (!exposed.equals("exposed_copper") || !stairs.equals("waxed_oxidized_cut_copper_stairs")
                || !item.equals("weathered_cut_copper")) {
            return TestResult.fail("copper constants read as " + exposed + ", " + stairs + ", " + item);
        }
        return TestResult.success();
    }
}
