/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

import com.mojang.serialization.MapCodec;
import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyDensityFunction.ContextProvider;
import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyDensityFunction.FunctionContext;
import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyDensityFunction.NoiseHolder;
import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyDensityFunction.SinglePointContext;
import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyDensityFunction.Visitor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction.CompileContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Where old density function calls go on 26.3, and the samplers 26.3 gets for old functions. */
public final class LegacyDensityFunctions {

    // The old function's sampler running on this thread, so a host function it computes samples with the same context.
    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);
    // The cells the overworld and the Nether interpolated in before 26.3 moved cell sizes into each function.
    private static final int OVERWORLD_CELL_XZ = 4;
    private static final int OVERWORLD_CELL_Y = 8;
    private static final Map<DensityFunction, DensitySampler> DETACHED = Collections.synchronizedMap(new WeakHashMap<>());

    private LegacyDensityFunctions() {}

    /** The old {@code function.compute(context)}, for any density function. */
    public static double compute(DensityFunction function, FunctionContext context) {
        if (function instanceof LegacyDensityFunction old) return old.compute(context);
        Frame frame = FRAME.get();
        if (frame.sampler != null) {
            DensitySampler child = frame.sampler.child(function);
            if (frame.pass != null) return frame.pass.sample(child, context.blockX(), context.blockY(), context.blockZ());
            return child.sampleValue(frame.context, context.blockX(), context.blockY(), context.blockZ());
        }
        return detached(function).sampleValue(SamplerContext.EMPTY_UNCACHED, context.blockX(), context.blockY(),
                context.blockZ());
    }

    /** The old {@code function.fillArray(values, provider)}: one value per position the provider names. */
    public static void fillArray(DensityFunction function, double[] values, ContextProvider provider) {
        if (function instanceof LegacyDensityFunction old) {
            old.fillArray(values, provider);
            return;
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = compute(function, provider.forIndex(i));
        }
    }

    /** The old {@code function.mapAll(visitor)}: rebuilds the tree from the bottom up with what the visitor returns. */
    public static DensityFunction mapAll(DensityFunction function, Visitor visitor) {
        // A host rewrite of an old function reaches only its direct children; the rule recurses itself.
        if (visitor instanceof ChildRewrite rewrite) return rewrite.rule().rewrite(function);
        // So does mapChildren of a function built before 26.2.
        if (visitor instanceof ChildMapper mapper) return mapper.visitor().apply(function);
        if (function instanceof LegacyDensityFunction old) return old.mapAll(visitor);
        // Wiring compiles a host function whole.
        if (visitor instanceof Wiring) return visitor.apply(function);
        // A reference stays a reference to its mapped value, which old visitors look for.
        if (function instanceof DensityFunctions.HolderHolder reference) {
            DensityFunction mapped = mapAll(reference.function().value(), visitor);
            return visitor.apply(new DensityFunctions.HolderHolder(Holder.direct(mapped)));
        }
        return visitor.apply(function.rewriteChildren(child -> mapAll(child, visitor)));
    }

    /** The 26.2 {@code function.mapChildren(visitor)}: the visitor applied to each direct child, the node rebuilt. */
    public static DensityFunction mapChildren(DensityFunction function, Visitor visitor) {
        if (function instanceof LegacyDensityFunction old) return old.mapChildren(visitor);
        if (function instanceof DensityFunctions.HolderHolder reference) {
            return new DensityFunctions.HolderHolder(Holder.direct(visitor.apply(reference.function().value())));
        }
        return function.rewriteChildren(visitor::apply);
    }

    public static DensityFunction invert(DensityFunction function) {
        return function.reciprocal();
    }

    public static double minValue(DensityFunction function) {
        return function instanceof LegacyDensityFunction old ? old.minValue() : function.range().min();
    }

    public static double maxValue(DensityFunction function) {
        return function instanceof LegacyDensityFunction old ? old.maxValue() : function.range().max();
    }

    /** The old {@code clamp(double, double)}; 26.3 bounds are floats. */
    public static DensityFunction clamp(DensityFunction function, double min, double max) {
        return function.clamp((float) min, (float) max);
    }

    public static DensityFunction constant(double value) {
        return DensityFunctions.constant((float) value);
    }

    public static DensityFunction yClampedGradient(int fromY, int toY, double fromValue, double toValue) {
        return DensityFunctions.yClampedGradient(fromY, toY, (float) fromValue, (float) toValue);
    }

    /** The old interpolated(input), which took its cells from the noise settings; 26.3 names them, so this uses the overworld's. */
    public static DensityFunction interpolated(DensityFunction input) {
        return DensityFunctions.interpolated(input, OVERWORLD_CELL_XZ, OVERWORLD_CELL_Y);
    }

    /** The old two-argument NoiseHolder constructor: wired when it is handed a 26.3 noise, otherwise unwired. */
    public static NoiseHolder noiseHolder(Holder<NormalNoise> noiseData, Object noise) {
        return new NoiseHolder(noiseData, noise instanceof Noise sampler ? sampler : null);
    }

    /** A noise read by an old function; in a volume, a repeated read of the same point reuses its value. */
    static double noise(Noise noise, double x, double y, double z) {
        VolumePass pass = FRAME.get().pass;
        return pass != null ? pass.noise(noise, x, y, z) : noise.get(x, y, z);
    }

    /** The coordinates an old function reads itself, with what its children depend on. */
    static int domainAxes(LegacyDensityFunction function) {
        int own = function.retromod$readAxes();
        if (own == DensityFunction.ALL_AXES) return own;
        int[] axes = {own};
        function.mapChildren(child -> {
            axes[0] |= child.domainAxes();
            return child;
        });
        return axes[0];
    }

    // 26.3's climate sampler holds bound samplers, so each getter reads as an old function again.
    public static DensityFunction temperature(Climate.Sampler sampler) {
        return new BoundFunction(sampler.temperature());
    }

    public static DensityFunction humidity(Climate.Sampler sampler) {
        return new BoundFunction(sampler.humidity());
    }

    public static DensityFunction continentalness(Climate.Sampler sampler) {
        return new BoundFunction(sampler.continentalness());
    }

    public static DensityFunction erosion(Climate.Sampler sampler) {
        return new BoundFunction(sampler.erosion());
    }

    public static DensityFunction depth(Climate.Sampler sampler) {
        return new BoundFunction(sampler.depth());
    }

    public static DensityFunction weirdness(Climate.Sampler sampler) {
        return new BoundFunction(sampler.weirdness());
    }

    /** Wires an old function as a world's random state did, then samples it through its compute. */
    static DensitySampler compileOld(LegacyDensityFunction function, CompileContext context) {
        DensityFunction wired = function.mapAll(new Wiring(context));
        if (wired instanceof CompiledChild child) return child.sampler;
        if (wired instanceof LegacyDensityFunction old) return new OldSampler(old, context);
        return compileHost(wired, context);
    }

    static DensityFunction rewriteOld(LegacyDensityFunction function, DfRewriteRule rule) {
        return function.mapAll(new ChildRewrite(rule));
    }

    /** Applies {@code visitor} to a whole subtree, as 26.2's mapAll handed to each mapChildren. */
    static Visitor recursive(Visitor visitor) {
        return new Recursive(visitor);
    }

    /** mapChildren of a function built before 26.2, through its mapAll: each child is visited once, the node itself not. */
    static DensityFunction mapChildrenOld(LegacyDensityFunction function, Visitor visitor) {
        return function.mapAll(new ChildMapper(visitor));
    }

    /** The old bounds as a 26.3 interval, rounded outward to floats; bounds out of order mean unknown. */
    static Interval range(double min, double max) {
        if (!(min <= max)) return Interval.INFINITE;
        float low = (float) min;
        if (low > min) low = Math.nextDown(low);
        float high = (float) max;
        if (high < max) high = Math.nextUp(high);
        return Interval.of(low, high);
    }

    // 26.3 compiles a cache only once its compiler deduplicated it, so one the host rewrite never reached samples uncached.
    static DensitySampler compileHost(DensityFunction function, CompileContext context) {
        return withoutCaches(function).compileSampler(context);
    }

    private static DensityFunction withoutCaches(DensityFunction function) {
        if (function instanceof CacheFunction cache) return withoutCaches(cache.input());
        return function.rewriteChildren(LegacyDensityFunctions::withoutCaches);
    }

    private static DensitySampler detached(DensityFunction function) {
        DensitySampler sampler = DETACHED.get(function);
        if (sampler == null) {
            sampler = compileHost(function, new DetachedContext(function.getClass().getName()));
            DETACHED.put(function, sampler);
        }
        return sampler;
    }

    /** The sampler running on a thread, the context it was handed, and the volume it is filling, if any. */
    private static final class Frame {
        OldSampler sampler;
        SamplerContext context;
        VolumePass pass;
    }

    /** One volume an old function's sampler is filling, with each host function it computes sampled over it once. */
    private static final class VolumePass {
        private final SamplerContext context;
        private final DensityVolume volume;
        private final Map<DensitySampler, ScopedDensityBuffer> filled = new IdentityHashMap<>();
        private final List<ScopedDensityBuffer> acquired = new ArrayList<>();
        private final Map<Noise, double[]> lastNoise = new IdentityHashMap<>();
        private int index;
        private int x;
        private int y;
        private int z;

        VolumePass(SamplerContext context, DensityVolume volume) {
            this.context = context;
            this.volume = volume;
        }

        void moveTo(int index, int x, int y, int z) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        float sample(DensitySampler sampler, int blockX, int blockY, int blockZ) {
            int at = blockX == x && blockY == y && blockZ == z ? index : volume.indexOfBlock(blockX, blockY, blockZ);
            // A point outside the volume, such as a shifted read, is sampled on its own.
            if (at == DensityVolume.NO_BLOCK) return sampler.sampleValue(context, blockX, blockY, blockZ);
            ScopedDensityBuffer values = filled.get(sampler);
            if (values == null) {
                values = context.acquireBuffer(volume);
                acquired.add(values);
                sampler.sampleVolume(context, values, volume);
                filled.put(sampler, values);
            }
            return values.get(at);
        }

        // A noise is a pure function of its arguments, so a column that reads it at one height reads it once.
        double noise(Noise noise, double noiseX, double noiseY, double noiseZ) {
            double[] last = lastNoise.get(noise);
            if (last == null) {
                last = new double[] {Double.NaN, Double.NaN, Double.NaN, 0};
                lastNoise.put(noise, last);
            } else if (last[0] == noiseX && last[1] == noiseY && last[2] == noiseZ) {
                return last[3];
            }
            float value = noise.get(noiseX, noiseY, noiseZ);
            last[0] = noiseX;
            last[1] = noiseY;
            last[2] = noiseZ;
            last[3] = value;
            return value;
        }

        void close() {
            for (ScopedDensityBuffer values : acquired) values.close();
        }
    }

    /** The host's rewrite rule, which mapAll applies once to each direct child; the old function itself stays. */
    private record ChildRewrite(DfRewriteRule rule) implements Visitor {
        @Override
        public DensityFunction apply(DensityFunction function) {
            return function;
        }
    }

    /** mapAll's visitor, handed to mapChildren, that carries the visit down into each child. */
    private record Recursive(Visitor visitor) implements Visitor {
        @Override
        public DensityFunction apply(DensityFunction function) {
            return mapAll(function, visitor);
        }

        @Override
        public NoiseHolder visitNoise(NoiseHolder noise) {
            return visitor.visitNoise(noise);
        }
    }

    /** The visitor of mapChildren, which mapAll applies once to each direct child; the node itself stays. */
    private record ChildMapper(Visitor visitor) implements Visitor {
        @Override
        public DensityFunction apply(DensityFunction function) {
            return function;
        }

        @Override
        public NoiseHolder visitNoise(NoiseHolder noise) {
            return visitor.visitNoise(noise);
        }
    }

    /** Compiles each host child of an old function and instantiates each noise it reads. */
    private record Wiring(CompileContext context) implements Visitor {
        @Override
        public DensityFunction apply(DensityFunction function) {
            if (function instanceof LegacyDensityFunction) return function;
            return new CompiledChild(function, compileHost(function, context));
        }

        @Override
        public NoiseHolder visitNoise(NoiseHolder noise) {
            return new NoiseHolder(noise.noiseData(), context.createNoiseSampler(noise.noiseData()));
        }
    }

    /** Samples an old function at one position at a time through its compute. */
    private static final class OldSampler implements DensitySampler {
        private final LegacyDensityFunction root;
        private final CompileContext context;
        // Host functions the old code computes without wiring them, compiled on first use.
        // The map is replaced whole, so a read needs no lock.
        private volatile Map<DensityFunction, DensitySampler> children = new IdentityHashMap<>();

        OldSampler(LegacyDensityFunction root, CompileContext context) {
            this.root = root;
            this.context = context;
        }

        @Override
        public float sampleValue(SamplerContext samplerContext, int x, int y, int z) {
            Frame frame = FRAME.get();
            OldSampler outerSampler = frame.sampler;
            SamplerContext outerContext = frame.context;
            VolumePass outerPass = frame.pass;
            frame.sampler = this;
            frame.context = samplerContext;
            frame.pass = null;
            try {
                return (float) root.compute(new SinglePointContext(x, y, z));
            } finally {
                frame.sampler = outerSampler;
                frame.context = outerContext;
                frame.pass = outerPass;
            }
        }

        /** Computes each point through the old function, which reads its host children from their own volume samples. */
        @Override
        public void sampleVolume(SamplerContext samplerContext, DensityBuffer buffer, DensityVolume volume) {
            Frame frame = FRAME.get();
            OldSampler outerSampler = frame.sampler;
            SamplerContext outerContext = frame.context;
            VolumePass outerPass = frame.pass;
            VolumePass pass = new VolumePass(samplerContext, volume);
            frame.sampler = this;
            frame.context = samplerContext;
            frame.pass = pass;
            try {
                // The host's order: z, then x, then y, one buffer index after another.
                int index = 0;
                for (int stepZ = 0; stepZ < volume.sizeZ(); stepZ++) {
                    int z = volume.blockZ(stepZ);
                    for (int stepX = 0; stepX < volume.sizeX(); stepX++) {
                        int x = volume.blockX(stepX);
                        for (int stepY = 0; stepY < volume.sizeY(); stepY++) {
                            int y = volume.blockY(stepY);
                            pass.moveTo(index, x, y, z);
                            buffer.set(index++, (float) root.compute(new SinglePointContext(x, y, z)));
                        }
                    }
                }
            } finally {
                pass.close();
                frame.sampler = outerSampler;
                frame.context = outerContext;
                frame.pass = outerPass;
            }
        }

        DensitySampler child(DensityFunction function) {
            DensitySampler sampler = children.get(function);
            if (sampler != null) return sampler;
            synchronized (this) {
                sampler = children.get(function);
                if (sampler == null) {
                    sampler = compileHost(function, context);
                    Map<DensityFunction, DensitySampler> grown = new IdentityHashMap<>(children);
                    grown.put(function, sampler);
                    children = grown;
                }
            }
            return sampler;
        }
    }

    /** A host function inside an old one, compiled once and sampled where the old function computes it. */
    static final class CompiledChild implements LegacyDensityFunction.SimpleFunction {
        private final DensityFunction function;
        final DensitySampler sampler;

        CompiledChild(DensityFunction function, DensitySampler sampler) {
            this.function = function;
            this.sampler = sampler;
        }

        @Override
        public double compute(FunctionContext context) {
            Frame frame = FRAME.get();
            if (frame.pass != null) return frame.pass.sample(sampler, context.blockX(), context.blockY(), context.blockZ());
            SamplerContext samplerContext = frame.sampler != null ? frame.context : SamplerContext.EMPTY_UNCACHED;
            return sampler.sampleValue(samplerContext, context.blockX(), context.blockY(), context.blockZ());
        }

        @Override
        public double minValue() {
            return function.range().min();
        }

        @Override
        public double maxValue() {
            return function.range().max();
        }

        @Override
        public DensitySampler compileSampler(CompileContext context) {
            return sampler;
        }

        @Override
        public Interval range() {
            return function.range();
        }

        @Override
        public int domainAxes() {
            return function.domainAxes();
        }

        /** Never written back to data: it exists only inside a compiled sampler. */
        @Override
        public MapCodec<? extends DensityFunction> codec() {
            return MapCodec.unit(this);
        }
    }

    /** One of a climate sampler's bound samplers, read as an old function. */
    static final class BoundFunction implements LegacyDensityFunction.SimpleFunction {
        private final DensitySampler.Bound bound;

        BoundFunction(DensitySampler.Bound bound) {
            this.bound = bound;
        }

        @Override
        public double compute(FunctionContext context) {
            return bound.sampleValue(context.blockX(), context.blockY(), context.blockZ());
        }

        // A bound sampler does not report its range.
        @Override
        public double minValue() {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return Double.POSITIVE_INFINITY;
        }

        /** Never written back to data: it wraps a world's sampler. */
        @Override
        public MapCodec<? extends DensityFunction> codec() {
            return MapCodec.unit(this);
        }
    }

    /** Compiles a function sampled outside world generation, where no world can seed a noise or random. */
    private record DetachedContext(String function) implements CompileContext {
        @Override
        public Noise createNoiseSampler(Holder<NormalNoise> noise) {
            throw outsideWorldGeneration(function);
        }

        @Override
        public RandomSource createRandom(Identifier id) {
            throw outsideWorldGeneration(function);
        }

        @Override
        public RandomSource createEndIslandRandom() {
            throw outsideWorldGeneration(function);
        }
    }

    private static IllegalStateException outsideWorldGeneration(String function) {
        return new IllegalStateException("A mod computed the density function " + function + " through the old"
                + " compute, outside world generation. It reads a noise or a random value, and Minecraft 26.3 creates"
                + " those only while it builds a world's samplers, so Retromod has no seed to sample it with.");
    }
}
