/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** Old density functions of the mod's own, computed and walked through the old API, as Tectonic and Lithostitched use them; 26.3 compiles them. */
final class DensityFunctionCases {

    private DensityFunctionCases() {}

    /** An old function over a host gradient computes, and mapAll swaps a marker under a reference in a host tree. */
    static boolean computesAndSwaps() {
        // From -1 at y = -64 to 1 at y = 320, so 0.5 at y = 224, scaled to 1.
        DensityFunction gradient = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);
        double scaled = new Scale(gradient, 2.0).compute(new DensityFunction.SinglePointContext(0, 224, 0));
        if (Math.abs(scaled - 1.0) > 1e-4) return false;
        DensityFunction tree = DensityFunctions.add(DensityFunctions.constant(1.0),
                new DensityFunctions.HolderHolder(Holder.direct(new Marker())));
        DensityFunction swapped = tree.mapAll(f -> f instanceof Marker ? DensityFunctions.constant(2.0) : f);
        return Math.abs(swapped.compute(new DensityFunction.SinglePointContext(5, 64, 7)) - 3.0) <= 1e-4;
    }

    /** A function of the mod's own with a child, written against the old API. */
    record Scale(DensityFunction input, double factor) implements DensityFunction {
        @Override
        public double compute(FunctionContext context) {
            return input.compute(context) * factor;
        }

        @Override
        public void fillArray(double[] values, ContextProvider provider) {
            provider.fillAllDirectly(values, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new Scale(input.mapAll(visitor), factor));
        }

        @Override
        public double minValue() {
            return Math.min(input.minValue() * factor, input.maxValue() * factor);
        }

        @Override
        public double maxValue() {
            return Math.max(input.minValue() * factor, input.maxValue() * factor);
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return null;
        }
    }

    /** A placeholder that must be swapped before it is computed. */
    record Marker() implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext context) {
            throw new IllegalStateException("the marker was never swapped");
        }

        @Override
        public double minValue() {
            return 0;
        }

        @Override
        public double maxValue() {
            return 0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return null;
        }
    }
}
