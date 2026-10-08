/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hold My Items' particle pipeline turns depth testing and writes off (#281). */
class LegacyDepthTestTest {

    public enum CompareOp { ALWAYS_PASS, LESS_THAN_OR_EQUAL, LESS_THAN, EQUAL, GREATER_THAN }

    public record DepthStencilState(CompareOp test, boolean write) {}

    /** Mirrors the two 26.x builder overloads the stand-in looks up reflectively. */
    public static final class Builder {
        Object depth = "unset";

        public Builder withDepthStencilState(DepthStencilState state) {
            depth = state;
            return this;
        }

        public Builder withDepthStencilState(Optional<DepthStencilState> state) {
            depth = state;
            return this;
        }
    }

    @Test
    void noDepthTestWithoutWritesRemovesTheDepthState() {
        Builder builder = new Builder();

        LegacyDepthTest.withDepthTestFunction(builder, LegacyDepthTest.NO_DEPTH_TEST);
        LegacyDepthTest.withDepthWrite(builder, false);

        assertEquals(Optional.empty(), builder.depth,
                "an always-passing test with no writes is the same as no depth state");
    }

    @Test
    void depthWriteKeepsTheEarlierComparison() {
        Builder builder = new Builder();

        LegacyDepthTest.withDepthTestFunction(builder, LegacyDepthTest.LESS_DEPTH_TEST);
        LegacyDepthTest.withDepthWrite(builder, false);

        assertEquals(new DepthStencilState(CompareOp.LESS_THAN, false), builder.depth);
    }
}
