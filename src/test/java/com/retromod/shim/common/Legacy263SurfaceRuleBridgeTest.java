/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old surface rule trees, built and applied through the old API, give 26.3's material rule evaluator the same blocks. */
class Legacy263SurfaceRuleBridgeTest {

    private static final String LEVELGEN = "net/minecraft/world/level/levelgen/";
    private static final String MATERIAL = "net.minecraft.world.level.levelgen.material.";

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old rule tree with the mod's own rule and condition picks the same block at every position on 26.3")
    void sameBlocksOnAGrid() throws Throwable {
        Map<String, byte[]> oldMod = only(compile(dir.resolve("old"), withOldHost(MOD)), "mod/");
        Run old = new Run(compile(dir.resolve("old-host"), OLD_HOST), oldMod);
        List<String> expected = grid(old, "net.minecraft.world.level.levelgen.SurfaceRules$Context", false);
        // The old run itself must pick several blocks, or the comparison would prove little.
        assertTrue(new LinkedHashSet<>(expected).size() >= 6, "the old tree picks varied blocks: " + new LinkedHashSet<>(expected));

        Map<String, byte[]> translated = transform("26.3", Legacy263SurfaceRuleBridgeTest::register, oldMod);
        Run current = new Run(compile(dir.resolve("new-host"), NEW_HOST), translated);
        assertEquals(expected, grid(current, "net.minecraft.world.level.levelgen.material.MaterialRuleContext", false),
                "26.3 compiling the old tree");
        assertEquals(expected, grid(current, "net.minecraft.world.level.levelgen.material.MaterialRuleContext", true),
                "the mod applying its own tree the old way");
    }

    @Test
    @DisplayName("the mod's own rule source gains the compile 26.3 calls, and the old constants and isBiome need no server")
    void ownRuleCompilesAndConstantsResolve() throws Throwable {
        Map<String, byte[]> translated = transform("26.3", Legacy263SurfaceRuleBridgeTest::register,
                only(compile(dir.resolve("old"), withOldHost(MOD)), "mod/"));
        ClassNode own = node(translated.get("mod/Rules$OwnRule"));
        assertTrue(own.methods.stream().anyMatch(m -> m.name.equals("compile")
                && m.desc.equals("(L" + LEVELGEN + "material/MaterialRuleContext;)L" + LEVELGEN + "material/rule/RuleEvaluator;")),
                "the host calls compile on every rule");
        List<String> reads = fields(translated.get("mod/Rules"), "build");
        assertTrue(reads.stream().anyMatch(read -> read.startsWith(Legacy263SurfaceRuleBridge.HELPER + ".ON_FLOOR")), reads.toString());
        assertTrue(calls(translated.get("mod/Rules"), "redDesert").contains(Legacy263SurfaceRuleBridge.HELPER
                + ".isBiome([Lnet/minecraft/resources/ResourceKey;)L" + LEVELGEN + "material/condition/MaterialCondition;"));
        assertTrue(calls(translated.get("mod/Rules"), "build").stream().anyMatch(call -> call.contains("noiseCondition2d")));
    }

    @Test
    @DisplayName("a biome the chunk cannot hold never matches, without reading the biome at each block")
    void biomeOutsideTheChunkNeverMatches() throws Throwable {
        Map<String, byte[]> translated = transform("26.3", Legacy263SurfaceRuleBridgeTest::register,
                only(compile(dir.resolve("old"), withOldHost(MOD)), "mod/"));
        Run current = new Run(compile(dir.resolve("new-host"), NEW_HOST), translated);
        Object condition = current.call("mod/Rules", "redDesert");
        Class<?> holder = current.loadClass("net.minecraft.core.Holder$Reference");
        Class<?> context = current.loadClass(MATERIAL + "MaterialRuleContext");
        Object plains = holder.getMethod("of", String.class).invoke(null, "minecraft:plains");
        Object chunk = context.getConstructor(Set.class).newInstance(Set.of(plains));
        Object evaluator = current.loadClass(MATERIAL + "condition.MaterialCondition").getMethod("compile", context)
                .invoke(condition, chunk);
        // Never moved, so a read of the biome would fail.
        assertFalse((boolean) current.loadClass(MATERIAL + "condition.ConditionEvaluator").getMethod("test").invoke(evaluator));
    }

    @Test
    @DisplayName("the bridge stays off a host that still has surface rules, and needs 26.3 offline")
    void gate() {
        assertFalse(Legacy263SurfaceRuleBridge.hostHasMaterialRules(new Object(), null, true), "26.2 keeps surface rules");
        assertTrue(Legacy263SurfaceRuleBridge.hostHasMaterialRules(null, new Object(), true), "26.3");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(Legacy263SurfaceRuleBridge.hostHasMaterialRules(null, null, false), "offline 26.2");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(Legacy263SurfaceRuleBridge.hostHasMaterialRules(null, null, false), "offline 26.3");
    }

    /** What the 26.3 shims register for the surface rule API, in their order. */
    static void register(RetromodTransformer t) {
        LegacyKeyDispatchCodecBridge.registerRemovedClass(t);
        LegacyKeyDispatchCodecBridge.registerAccessorBridges(t);
        Mc26_2To26_3CoreMoves.registerSurfaceRuleMoves(t);
    }

    /** The block each grid position gets, as a world generator would: build once per chunk, then move and ask. */
    private static List<String> grid(Run run, String contextClass, boolean modApplies) throws Throwable {
        Object rule = run.loadClass("mod.Rules").getField("RULE").get(null);
        Class<?> holder = run.loadClass("net.minecraft.core.Holder$Reference");
        Method of = holder.getMethod("of", String.class);
        Object desert = of.invoke(null, "mod:red_desert");
        Object forest = of.invoke(null, "mod:forest");
        Object plains = of.invoke(null, "minecraft:plains");
        Class<?> context = run.loadClass(contextClass);
        Object chunk = context.getConstructor(Set.class).newInstance(Set.of(desert, forest, plains));
        boolean old = contextClass.endsWith("SurfaceRules$Context");
        Object evaluator = modApplies || old
                ? run.call("mod/Rules", "evaluate", rule, chunk)
                : run.loadClass(MATERIAL + "rule.MaterialRule").getMethod("compile", context).invoke(rule, chunk);
        Method move = context.getMethod("moveTo", run.loadClass("net.minecraft.core.Holder"),
                int.class, int.class, int.class, int.class, int.class, int.class);
        Method tryApply = run.loadClass(old ? "net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule"
                : MATERIAL + "rule.RuleEvaluator").getMethod("tryApply", int.class, int.class, int.class);
        List<String> blocks = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                Object biome = x < 2 ? desert : z < 2 ? forest : plains;
                for (int y = 60; y <= 72; y++) {
                    move.invoke(chunk, biome, x, y, z, (72 - y) % 4, y % 3, 1 + (x + z) % 3);
                    blocks.add(x + "," + y + "," + z + "=" + tryApply.invoke(evaluator, x, y, z));
                }
            }
        }
        return blocks;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Map<String, String> withOldHost(Map<String, String> mod) {
        Map<String, String> sources = new HashMap<>(OLD_HOST);
        sources.putAll(mod);
        return sources;
    }

    /** Shared by both hosts: keys, holders, block states, anchors, and a noise that varies with x and z. */
    private static final Map<String, String> COMMON = Map.of(
            "net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {
                        private static final java.util.Map<String, ResourceKey<Object>> KEYS = new java.util.HashMap<>();
                        private final String id;
                        private ResourceKey(String id) { this.id = id; }
                        public static synchronized ResourceKey<Object> of(String id) { return KEYS.computeIfAbsent(id, ResourceKey::new); }
                        @Override public String toString() { return id; }
                    }""",
            "net/minecraft/core/Holder.java", """
                    package net.minecraft.core;
                    import net.minecraft.resources.ResourceKey;
                    public interface Holder<T> {
                        boolean is(ResourceKey<T> key);
                        T value();
                        static <T> Holder<T> direct(T value) { throw new UnsupportedOperationException(); }
                        final class Reference<T> implements Holder<T> {
                            private final ResourceKey<Object> key;
                            private Reference(ResourceKey<Object> key) { this.key = key; }
                            public static Reference<Object> of(String id) { return new Reference<>(ResourceKey.of(id)); }
                            public boolean is(ResourceKey<T> other) { return (Object) key == other; }
                            public T value() { return null; }
                        }
                    }""",
            "net/minecraft/world/level/block/state/BlockState.java", """
                    package net.minecraft.world.level.block.state;
                    public final class BlockState {
                        private static final java.util.Map<String, BlockState> STATES = new java.util.HashMap<>();
                        private final String name;
                        private BlockState(String name) { this.name = name; }
                        public static synchronized BlockState of(String name) { return STATES.computeIfAbsent(name, BlockState::new); }
                        @Override public String toString() { return name; }
                    }""",
            "net/minecraft/world/level/levelgen/placement/CaveSurface.java",
            "package net.minecraft.world.level.levelgen.placement; public enum CaveSurface { CEILING, FLOOR }",
            "net/minecraft/world/level/levelgen/VerticalAnchor.java", """
                    package net.minecraft.world.level.levelgen;
                    public final class VerticalAnchor {
                        public final int y;
                        private VerticalAnchor(int y) { this.y = y; }
                        public static VerticalAnchor absolute(int y) { return new VerticalAnchor(y); }
                    }""",
            "net/minecraft/world/level/levelgen/FakeNoise.java", """
                    package net.minecraft.world.level.levelgen;
                    public final class FakeNoise {
                        public static double at(int x, int z) { return ((x * 31 + z * 17) % 10) / 10.0; }
                    }""");

    /** The old API with the old evaluation: a rule or condition is a function of the context. */
    private static final Map<String, String> OLD_HOST = merge(COMMON, Map.of(
            "net/minecraft/util/KeyDispatchDataCodec.java",
            "package net.minecraft.util; public final class KeyDispatchDataCodec<A> {}",
            "net/minecraft/world/level/levelgen/SurfaceRules.java", """
                    package net.minecraft.world.level.levelgen;
                    import java.util.*;
                    import java.util.function.*;
                    import net.minecraft.core.Holder;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.block.state.BlockState;
                    import net.minecraft.world.level.levelgen.placement.CaveSurface;
                    public class SurfaceRules {
                        public static final ConditionSource ON_FLOOR = stoneDepthCheck(0, false, CaveSurface.FLOOR);
                        public static final ConditionSource UNDER_FLOOR = stoneDepthCheck(0, true, CaveSurface.FLOOR);
                        public static final ConditionSource ON_CEILING = stoneDepthCheck(0, false, CaveSurface.CEILING);
                        public interface Condition { boolean test(); }
                        public interface SurfaceRule { BlockState tryApply(int x, int y, int z); }
                        public interface ConditionSource extends Function<Context, Condition> { KeyDispatchDataCodec<? extends ConditionSource> codec(); }
                        public interface RuleSource extends Function<Context, SurfaceRule> { KeyDispatchDataCodec<? extends RuleSource> codec(); }
                        public static final class Context {
                            public Supplier<Holder<Object>> biome;
                            public int blockX, blockY, blockZ, stoneDepthAbove, stoneDepthBelow, surfaceDepth;
                            public Context(Set<Holder<Object>> chunkBiomes) {}
                            public void moveTo(Holder<Object> at, int x, int y, int z, int above, int below, int depth) {
                                biome = () -> at; blockX = x; blockY = y; blockZ = z; stoneDepthAbove = above; stoneDepthBelow = below; surfaceDepth = depth;
                            }
                        }
                        abstract static class Source implements ConditionSource { public KeyDispatchDataCodec<? extends ConditionSource> codec() { return null; } }
                        abstract static class Rule implements RuleSource { public KeyDispatchDataCodec<? extends RuleSource> codec() { return null; } }
                        public static ConditionSource stoneDepthCheck(int offset, boolean addSurfaceDepth, CaveSurface surface) {
                            return new Source() { public Condition apply(Context c) { return () -> (surface == CaveSurface.CEILING ? c.stoneDepthBelow : c.stoneDepthAbove) <= 1 + offset + (addSurfaceDepth ? c.surfaceDepth : 0); } };
                        }
                        public static ConditionSource not(ConditionSource source) {
                            return new Source() { public Condition apply(Context c) { Condition inner = source.apply(c); return () -> !inner.test(); } };
                        }
                        public static ConditionSource yBlockCheck(VerticalAnchor anchor, int multiplier) {
                            return new Source() { public Condition apply(Context c) { return () -> c.blockY >= anchor.y + c.surfaceDepth * multiplier; } };
                        }
                        @SafeVarargs
                        public static ConditionSource isBiome(ResourceKey<Object>... keys) {
                            return new Source() { public Condition apply(Context c) { return () -> { for (ResourceKey<Object> k : keys) if (c.biome.get().is(k)) return true; return false; }; } };
                        }
                        public static ConditionSource noiseCondition(ResourceKey<Object> noise, double min) {
                            return new Source() { public Condition apply(Context c) { return () -> FakeNoise.at(c.blockX, c.blockZ) >= min; } };
                        }
                        public static RuleSource ifTrue(ConditionSource condition, RuleSource rule) {
                            return new Rule() { public SurfaceRule apply(Context c) { Condition test = condition.apply(c); SurfaceRule then = rule.apply(c); return (x, y, z) -> test.test() ? then.tryApply(x, y, z) : null; } };
                        }
                        public static RuleSource sequence(RuleSource... rules) {
                            return new Rule() { public SurfaceRule apply(Context c) {
                                List<SurfaceRule> applied = new ArrayList<>();
                                for (RuleSource r : rules) applied.add(r.apply(c));
                                return (x, y, z) -> { for (SurfaceRule r : applied) { BlockState s = r.tryApply(x, y, z); if (s != null) return s; } return null; };
                            } };
                        }
                        public static RuleSource state(BlockState state) {
                            return new Rule() { public SurfaceRule apply(Context c) { return (x, y, z) -> state; } };
                        }
                    }"""));

    /** 26.3's shape with the same results: rules and conditions compile against a context with accessors. */
    /** The holder sets and owners the rebuilt biome condition reads, which the verifier loads with it. */
    private static final Map<String, String> HOLDER_SETS = Map.of(
            "net/minecraft/core/HolderOwner.java", "package net.minecraft.core; public interface HolderOwner<T> {}",
            "net/minecraft/core/HolderSet.java", """
                    package net.minecraft.core;
                    public interface HolderSet<T> {
                        java.util.stream.Stream<Holder<T>> stream();
                        static <T> Direct<T> direct(java.util.List<? extends Holder<T>> holders) { throw new UnsupportedOperationException(); }
                        abstract class Direct<T> implements HolderSet<T> {}
                    }""");

    private static final Map<String, String> NEW_HOST = merge(merge(COMMON, HOLDER_SETS), Map.of(
            "com/mojang/serialization/MapCodec.java", """
                    package com.mojang.serialization;
                    public abstract class MapCodec<A> { public static <A> MapCodec<A> unit(A value) { return null; } }""",
            "com/mojang/serialization/Codec.java", "package com.mojang.serialization; public interface Codec<A> {}",
            "net/minecraft/world/level/levelgen/material/rule/RuleEvaluator.java", """
                    package net.minecraft.world.level.levelgen.material.rule;
                    public interface RuleEvaluator { net.minecraft.world.level.block.state.BlockState tryApply(int x, int y, int z); }""",
            "net/minecraft/world/level/levelgen/material/rule/MaterialRule.java", """
                    package net.minecraft.world.level.levelgen.material.rule;
                    public interface MaterialRule {
                        RuleEvaluator compile(net.minecraft.world.level.levelgen.material.MaterialRuleContext context);
                        com.mojang.serialization.MapCodec<? extends MaterialRule> codec();
                    }""",
            "net/minecraft/world/level/levelgen/material/condition/ConditionEvaluator.java",
            "package net.minecraft.world.level.levelgen.material.condition; public interface ConditionEvaluator { boolean test(); }",
            "net/minecraft/world/level/levelgen/material/condition/MaterialCondition.java", """
                    package net.minecraft.world.level.levelgen.material.condition;
                    public interface MaterialCondition {
                        ConditionEvaluator compile(net.minecraft.world.level.levelgen.material.MaterialRuleContext context);
                        com.mojang.serialization.MapCodec<? extends MaterialCondition> codec();
                    }""",
            "net/minecraft/world/level/levelgen/material/MaterialRuleContext.java", """
                    package net.minecraft.world.level.levelgen.material;
                    import java.util.Set;
                    import net.minecraft.core.Holder;
                    import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
                    public final class MaterialRuleContext {
                        private final Set<Holder<Object>> possibleBiomes;
                        private Holder<Object> biome;
                        private int blockX, blockY, blockZ, stoneDepthAbove, stoneDepthBelow, surfaceDepth;
                        long lastUpdateY;
                        public MaterialRuleContext(Set<Holder<Object>> possibleBiomes) { this.possibleBiomes = possibleBiomes; }
                        public void moveTo(Holder<Object> at, int x, int y, int z, int above, int below, int depth) {
                            biome = at; blockX = x; blockY = y; blockZ = z; stoneDepthAbove = above; stoneDepthBelow = below; surfaceDepth = depth; lastUpdateY++;
                        }
                        public Holder<Object> getBiome() { if (biome == null) throw new IllegalStateException("not moved yet"); return biome; }
                        public Set<Holder<Object>> possibleBiomes() { return possibleBiomes; }
                        public int blockX() { return blockX; }
                        public int blockY() { return blockY; }
                        public int blockZ() { return blockZ; }
                        public int stoneDepthAbove() { return stoneDepthAbove; }
                        public int stoneDepthBelow() { return stoneDepthBelow; }
                        public int surfaceDepth() { return surfaceDepth; }
                        public abstract static class LazyYCondition implements ConditionEvaluator {
                            protected final MaterialRuleContext context;
                            private long lastUpdate = -1;
                            private boolean result;
                            protected LazyYCondition(MaterialRuleContext context) { this.context = context; }
                            public boolean test() { if (context.lastUpdateY != lastUpdate) { lastUpdate = context.lastUpdateY; result = compute(); } return result; }
                            protected abstract boolean compute();
                        }
                    }""",
            "net/minecraft/world/level/levelgen/material/MaterialRules.java", """
                    package net.minecraft.world.level.levelgen.material;
                    import java.util.*;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.world.level.block.state.BlockState;
                    import net.minecraft.world.level.levelgen.FakeNoise;
                    import net.minecraft.world.level.levelgen.VerticalAnchor;
                    import net.minecraft.world.level.levelgen.material.condition.*;
                    import net.minecraft.world.level.levelgen.material.rule.*;
                    import net.minecraft.world.level.levelgen.placement.CaveSurface;
                    public class MaterialRules {
                        abstract static class Condition implements MaterialCondition { public com.mojang.serialization.MapCodec<? extends MaterialCondition> codec() { return null; } }
                        abstract static class Rule implements MaterialRule { public com.mojang.serialization.MapCodec<? extends MaterialRule> codec() { return null; } }
                        public static MaterialCondition stoneDepthCheck(int offset, boolean addSurfaceDepth, CaveSurface surface) { return stoneDepthCheck(offset, addSurfaceDepth, 0, surface); }
                        public static MaterialCondition stoneDepthCheck(int offset, boolean addSurfaceDepth, int secondary, CaveSurface surface) {
                            return new Condition() { public ConditionEvaluator compile(MaterialRuleContext c) { return () -> (surface == CaveSurface.CEILING ? c.stoneDepthBelow() : c.stoneDepthAbove()) <= 1 + offset + (addSurfaceDepth ? c.surfaceDepth() : 0); } };
                        }
                        public static MaterialCondition not(MaterialCondition condition) {
                            return new Condition() { public ConditionEvaluator compile(MaterialRuleContext c) { ConditionEvaluator inner = condition.compile(c); return () -> !inner.test(); } };
                        }
                        public static MaterialCondition yBlockCheck(VerticalAnchor anchor, int multiplier) {
                            return new Condition() { public ConditionEvaluator compile(MaterialRuleContext c) { return () -> c.blockY() >= anchor.y + c.surfaceDepth() * multiplier; } };
                        }
                        public static MaterialCondition noiseCondition2d(ResourceKey<Object> noise, double min) { return noiseCondition2d(noise, min, Double.MAX_VALUE); }
                        public static MaterialCondition noiseCondition2d(ResourceKey<Object> noise, double min, double max) {
                            return new Condition() { public ConditionEvaluator compile(MaterialRuleContext c) { return () -> { double v = FakeNoise.at(c.blockX(), c.blockZ()); return v >= min && v <= max; }; } };
                        }
                        public static MaterialRule ifTrue(MaterialCondition condition, MaterialRule rule) {
                            return new Rule() { public RuleEvaluator compile(MaterialRuleContext c) { ConditionEvaluator test = condition.compile(c); RuleEvaluator then = rule.compile(c); return (x, y, z) -> test.test() ? then.tryApply(x, y, z) : null; } };
                        }
                        public static MaterialRule sequence(MaterialRule... rules) {
                            return new Rule() { public RuleEvaluator compile(MaterialRuleContext c) {
                                List<RuleEvaluator> compiled = new ArrayList<>();
                                for (MaterialRule r : rules) compiled.add(r.compile(c));
                                return (x, y, z) -> { for (RuleEvaluator r : compiled) { BlockState s = r.tryApply(x, y, z); if (s != null) return s; } return null; };
                            } };
                        }
                        public static MaterialRule state(BlockState state) {
                            return new Rule() { public RuleEvaluator compile(MaterialRuleContext c) { return (x, y, z) -> state; } };
                        }
                    }"""));

    /** A Biomes O' Plenty style tree built at class init, a TerraBlender style rule of the mod's own, and a condition of its own. */
    private static final Map<String, String> MOD = Map.of("mod/Rules.java", """
            package mod;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.util.KeyDispatchDataCodec;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.levelgen.SurfaceRules;
            import net.minecraft.world.level.levelgen.VerticalAnchor;
            public class Rules {
                static final ResourceKey<Object> FOREST = ResourceKey.of("mod:forest");
                // Built before any server exists.
                public static final SurfaceRules.RuleSource RULE = build();

                static SurfaceRules.RuleSource build() {
                    return SurfaceRules.sequence(
                            SurfaceRules.ifTrue(redDesert(), SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(BlockState.of("red_sand")))),
                            SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(63), 0)), SurfaceRules.state(BlockState.of("deepslate"))),
                            SurfaceRules.ifTrue(new High(), SurfaceRules.state(BlockState.of("snow"))),
                            new OwnRule(SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, SurfaceRules.state(BlockState.of("moss")))),
                            SurfaceRules.ifTrue(SurfaceRules.noiseCondition(ResourceKey.of("mod:patches"), 0.6), SurfaceRules.state(BlockState.of("gravel"))),
                            SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, SurfaceRules.state(BlockState.of("basalt"))),
                            SurfaceRules.state(BlockState.of("stone")));
                }

                public static SurfaceRules.ConditionSource redDesert() {
                    return SurfaceRules.isBiome(ResourceKey.of("mod:red_desert"));
                }

                // The old way to run a rule, as TerraBlender's namespaced rule runs the ones it holds.
                public static SurfaceRules.SurfaceRule evaluate(SurfaceRules.RuleSource rule, SurfaceRules.Context context) {
                    return rule.apply(context);
                }

                /** Applies its rule only in the forest, reading the context's biome supplier as TerraBlender does. */
                static final class OwnRule implements SurfaceRules.RuleSource {
                    private final SurfaceRules.RuleSource inner;
                    OwnRule(SurfaceRules.RuleSource inner) { this.inner = inner; }
                    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
                        SurfaceRules.SurfaceRule applied = inner.apply(context);
                        return (x, y, z) -> context.biome.get().is(FOREST) ? applied.tryApply(x, y, z) : null;
                    }
                    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() { return null; }
                }

                /** A condition of the mod's own that reads the context's height field. */
                static final class High implements SurfaceRules.ConditionSource {
                    public SurfaceRules.Condition apply(SurfaceRules.Context context) { return () -> context.blockY > 70; }
                    public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return null; }
                }
            }""");

    private static Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        Map<String, String> merged = new HashMap<>(first);
        merged.putAll(second);
        return merged;
    }
}
