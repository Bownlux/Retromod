/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Old fluid handlers as old mods wrote them: classes outside Retromod's reach with their own renderFluid. */
public final class FakeFluidHandlers {

    private FakeFluidHandlers() {}

    /** Draws two whole quads. */
    static final class TwoQuads implements FakeFluidRenderHandler {
        public void renderFluid(FakeBlockPos pos, FakeBlockAndTintGetter level, FakeVertexConsumer out, FakeBlockState block,
                FakeFluidState fluid) {
            for (int vertex = 0; vertex < 8; vertex++) vertex(out, vertex);
        }
    }

    /** Draws one whole quad, then fails in the middle of the next vertex, as a call to a member 26.x removed would. */
    static final class FailsMidVertex implements FakeFluidRenderHandler {
        public void renderFluid(FakeBlockPos pos, FakeBlockAndTintGetter level, FakeVertexConsumer out, FakeBlockState block,
                FakeFluidState fluid) {
            for (int vertex = 0; vertex < 4; vertex++) vertex(out, vertex);
            out.addVertex(0, 1, 0).setColor(255, 255, 255, 255);
            throw new NoSuchMethodError("a vertex member 26.x removed");
        }
    }

    // The parts a block vertex needs: position, color, texture coordinates and light.
    static void vertex(FakeVertexConsumer out, int vertex) {
        out.addVertex(vertex, 1, 0).setColor(255, 255, 255, 255).setUv(0.1f * vertex, 0.1f).setUv2(240, 240).setNormal(0, 1, 0);
    }
}
