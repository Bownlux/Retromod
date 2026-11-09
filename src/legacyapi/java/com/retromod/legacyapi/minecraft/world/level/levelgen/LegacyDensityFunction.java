/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

import com.mojang.serialization.Codec;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** The old density function API, which 26.3 replaced with compiled samplers, kept on top of them. */
public interface LegacyDensityFunction extends DensityFunction {

    double compute(FunctionContext context);

    void fillArray(double[] values, ContextProvider provider);

    double minValue();

    double maxValue();

    /** A mod built for 26.2 implements mapChildren, and mapAll visits the whole tree through it. */
    default DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(mapChildren(LegacyDensityFunctions.recursive(visitor)));
    }

    /** A mod built before 26.2 implements mapAll, which reaches the children it rebuilds. */
    default DensityFunction mapChildren(Visitor visitor) {
        return LegacyDensityFunctions.mapChildrenOld(this, visitor);
    }

    /** 26.3 samples a compiled form; an old function compiles into one that calls its compute. */
    @Override
    default DensitySampler compileSampler(CompileContext context) {
        return LegacyDensityFunctions.compileOld(this, context);
    }

    /** 26.3 rewrites the direct children of a function; an old function reaches them through mapAll. */
    @Override
    default DensityFunction rewriteChildren(DfRewriteRule rule) {
        return LegacyDensityFunctions.rewriteOld(this, rule);
    }

    @Override
    default Interval range() {
        return LegacyDensityFunctions.range(minValue(), maxValue());
    }

    /** The axes the function reads itself, with those of its children; 26.3 samples fewer points for fewer axes. */
    @Override
    default int domainAxes() {
        return LegacyDensityFunctions.domainAxes(this);
    }

    /** The coordinates the function's own compute reads. Retromod writes this into a class it can read; otherwise all. */
    default int retromod$readAxes() {
        return ALL_AXES;
    }

    /** The old clamp, which a mod's own function inherits; 26.3 bounds are floats. */
    default DensityFunction clamp(double min, double max) {
        return clamp((float) min, (float) max);
    }

    /** The old invert, one over the value, which 26.3 calls reciprocal. */
    default DensityFunction invert() {
        return reciprocal();
    }

    /** The block position an old function is computed at. */
    interface FunctionContext {
        int blockX();

        int blockY();

        int blockZ();

        default Blender getBlender() {
            return Blender.empty();
        }
    }

    /** What filled an array of positions for the old fillArray; nothing in 26.3 provides one. */
    interface ContextProvider {
        FunctionContext forIndex(int index);

        void fillAllDirectly(double[] values, DensityFunction function);
    }

    /** An old function with no children, filled point by point and visited as itself. */
    interface SimpleFunction extends LegacyDensityFunction {
        @Override
        default void fillArray(double[] values, ContextProvider provider) {
            provider.fillAllDirectly(values, this);
        }

        @Override
        default DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(this);
        }

        @Override
        default DensityFunction mapChildren(Visitor visitor) {
            return this;
        }
    }

    /** One block position. */
    record SinglePointContext(int blockX, int blockY, int blockZ) implements FunctionContext {
    }

    /** Called on every node of a tree by mapAll, and on each noise the tree reads. */
    interface Visitor {
        DensityFunction apply(DensityFunction function);

        default NoiseHolder visitNoise(NoiseHolder noise) {
            return noise;
        }
    }

    /** A noise an old function reads, by its registry entry, and the instance once a world wired it. */
    final class NoiseHolder {
        public static final Codec<NoiseHolder> CODEC = NormalNoise.CODEC.xmap(NoiseHolder::new, NoiseHolder::noiseData);

        private final Holder<NormalNoise> noiseData;
        private final Noise noise;

        public NoiseHolder(Holder<NormalNoise> noiseData) {
            this(noiseData, null);
        }

        public NoiseHolder(Holder<NormalNoise> noiseData, Noise noise) {
            this.noiseData = noiseData;
            this.noise = noise;
        }

        public Holder<NormalNoise> noiseData() {
            return noiseData;
        }

        /** The wired noise, which 26.3 calls Noise, or null. */
        public Noise noise() {
            return noise;
        }

        // An unwired noise reads zero, as it always did.
        public double getValue(double x, double y, double z) {
            return noise == null ? 0.0 : LegacyDensityFunctions.noise(noise, x, y, z);
        }

        public double maxValue() {
            return noise == null ? 2.0 : noise.range().max();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NoiseHolder holder && noiseData.equals(holder.noiseData)
                    && Objects.equals(noise, holder.noise);
        }

        @Override
        public int hashCode() {
            return Objects.hash(noiseData, noise);
        }
    }
}
