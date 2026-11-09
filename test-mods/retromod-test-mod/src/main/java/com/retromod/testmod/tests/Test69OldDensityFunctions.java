/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;

/** Old density functions of the mod's own, computed and walked through the old API, as Tectonic and Lithostitched use them; 26.3 compiles them. */
public final class Test69OldDensityFunctions implements Test {

    @Override
    public String description() {
        return "an old density function computes over host functions, and mapAll swaps a marker under a reference";
    }

    @Override
    public TestResult run() {
        // From -1 at y = -64 to 1 at y = 320, so 0.5 at y = 224, scaled to 1.
        DensityFunction gradient = DensityFunctionTypes.yClampedGradient(-64, 320, -1.0, 1.0);
        double scaled = new Scale(gradient, 2.0).sample(new DensityFunction.UnblendedNoisePos(0, 224, 0));
        if (Math.abs(scaled - 1.0) > 1e-4) return TestResult.fail("the old function computed " + scaled + ", not 1.0");
        // Lithostitched's placeholder swap: the marker sits under a reference in a tree of host functions.
        DensityFunction tree = DensityFunctionTypes.add(DensityFunctionTypes.constant(1.0),
                new DensityFunctionTypes.RegistryEntryHolder(RegistryEntry.of(new Marker())));
        DensityFunction swapped = tree.apply(f -> f instanceof Marker ? DensityFunctionTypes.constant(2.0) : f);
        double sum = swapped.sample(new DensityFunction.UnblendedNoisePos(5, 64, 7));
        if (Math.abs(sum - 3.0) > 1e-4) return TestResult.fail("the swapped tree computed " + sum + ", not 3.0");
        return TestResult.success();
    }

    /** A function of the mod's own with a child, written against the old API. */
    record Scale(DensityFunction input, double factor) implements DensityFunction {
        @Override
        public double sample(NoisePos pos) {
            return input.sample(pos) * factor;
        }

        @Override
        public void fill(double[] values, EachApplier applier) {
            applier.fill(values, this);
        }

        @Override
        public DensityFunction apply(DensityFunctionVisitor visitor) {
            return visitor.apply(new Scale(input.apply(visitor), factor));
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
        public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return null;
        }
    }

    /** A placeholder that must be swapped before it is computed. */
    record Marker() implements DensityFunction.Base {
        @Override
        public double sample(NoisePos pos) {
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
        public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return null;
        }
    }
}
