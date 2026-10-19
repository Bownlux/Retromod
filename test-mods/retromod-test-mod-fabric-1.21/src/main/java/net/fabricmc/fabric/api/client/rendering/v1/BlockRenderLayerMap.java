/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.fabricmc.fabric.api.client.rendering.v1;

import net.minecraft.block.Block;
import net.minecraft.class_11515;
import net.minecraft.fluid.Fluid;

/** Compile-only copy of the 1.21.6 to 1.21.11 signatures, which the jar task leaves out. */
public final class BlockRenderLayerMap {
    private BlockRenderLayerMap() {}

    public static void putBlock(Block block, class_11515 layer) {
        throw new AssertionError("compile-only");
    }

    public static void putBlocks(class_11515 layer, Block... blocks) {
        throw new AssertionError("compile-only");
    }

    public static void putFluid(Fluid fluid, class_11515 layer) {
        throw new AssertionError("compile-only");
    }

    public static void putFluids(class_11515 layer, Fluid... fluids) {
        throw new AssertionError("compile-only");
    }
}
