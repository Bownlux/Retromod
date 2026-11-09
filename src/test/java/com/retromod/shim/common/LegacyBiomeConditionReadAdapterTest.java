/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Citadel groups surface rules by the first biome of their condition; an addon's old isBiome rule from another jar counts. */
class LegacyBiomeConditionReadAdapterTest {

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Citadel's embedded copy reads the biome of the addon's key condition and of 26.3's own condition")
    void citadelReadsEveryBiomeCondition() throws Throwable {
        Map<String, byte[]> host = compile(dir.resolve("host"), HOST);
        Map<String, byte[]> citadel = embedded("citadel", only(compile(dir.resolve("citadel"), merge(withField(), CITADEL)), "citadel/"));
        Map<String, byte[]> caves = embedded("alexscaves", only(compile(dir.resolve("caves"), merge(withIsBiome(), CAVES)), "caves/"));
        // Each jar carries its own copy of the rebuilt key condition.
        assertTrue(citadel.keySet().stream().anyMatch(name -> name.contains("/citadel") && name.endsWith("LegacyMaterialRules")), citadel.keySet().toString());
        assertTrue(caves.keySet().stream().anyMatch(name -> name.contains("/alexscaves") && name.endsWith("LegacyMaterialRules$KeyBiomeCondition")), caves.keySet().toString());

        Loader game = new Loader(host, LegacyBiomeConditionReadAdapterTest.class.getClassLoader());
        Loader citadelLoader = new Loader(citadel, game);
        Loader cavesLoader = new Loader(caves, game);
        Object addonRule = call(cavesLoader, "caves.Rules", "caveRule");
        Object vanillaRule = call(cavesLoader, "caves.Rules", "vanillaRule");
        Object grouped = call(citadelLoader, "citadel.Rules", "firstBiomes", List.of(addonRule, vanillaRule));

        assertEquals(List.of("alexscaves:primordial_caves", "minecraft:plains"), grouped);
    }

    @Test
    @DisplayName("only a method that reads the old biome list has its checks and casts of the condition rewritten")
    void onlyOldReadersAreRewritten() throws Exception {
        Map<String, byte[]> translated = translate(only(compile(dir.resolve("citadel"), merge(withField(), CITADEL)), "citadel/"));
        String helper = Legacy263SurfaceRuleBridge.HELPER;
        String condition = LegacyBiomeConditionReadAdapter.CONDITION;
        assertEquals(List.of(helper + ".isBiomeCondition(Ljava/lang/Object;)Z",
                helper + ".asBiomeCondition(Ljava/lang/Object;)L" + condition + ";",
                helper + ".biomes(L" + condition + ";)Ljava/util/List;",
                helper + ".biomes(L" + condition + ";)Ljava/util/List;"),
                calls(translated.get("citadel/Rules"), "firstBiomes").stream().filter(call -> call.startsWith(helper)).toList());
        assertTrue(calls(translated.get("citadel/Rules"), "isVanilla").stream().noneMatch(call -> call.startsWith(helper)),
                "a check that never reads the old list keeps 26.3's own type");
    }

    private Map<String, byte[]> translate(Map<String, byte[]> mod) {
        return transform("26.3", Legacy263SurfaceRuleBridgeTest::register, mod);
    }

    /** {@code mod} translated for 26.3 and embedded under its own key, as the jar the game loads. */
    private Map<String, byte[]> embedded(String key, Map<String, byte[]> mod) throws Exception {
        Map<String, byte[]> translated = translate(mod);
        ByteArrayOutputStream jar = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(jar)) {
            for (Map.Entry<String, byte[]> entry : translated.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        SyntheticEmbedder.ByteEmbeddingResult result = SyntheticEmbedder.embedIntoJarBytes(jar.toByteArray(), key,
                RetromodTransformer.getInstance());
        assertTrue(result.succeeded() && result.embeddedCount() > 0, key + " embeds the rebuilt classes");
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(result.jarBytes()))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.getName().endsWith(".class")) {
                    classes.put(entry.getName().substring(0, entry.getName().length() - ".class".length()), in.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static Object call(ClassLoader loader, String owner, String name, Object... args) throws Throwable {
        for (Method method : loader.loadClass(owner).getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                try {
                    return method.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new AssertionError("no method " + owner + "." + name);
    }

    /** One jar's classes over a parent, which they win over. */
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** The host as Citadel's code was compiled against it: the old condition kept its key list in a field. */
    private static Map<String, String> withField() {
        Map<String, String> sources = new HashMap<>(HOST);
        sources.put("net/minecraft/world/level/levelgen/material/condition/BiomeCondition.java", """
                package net.minecraft.world.level.levelgen.material.condition;
                public final class BiomeCondition implements MaterialCondition {
                    public java.util.List<Object> biomes;
                    public ConditionEvaluator compile(net.minecraft.world.level.levelgen.material.MaterialRuleContext c) { return null; }
                    public com.mojang.serialization.MapCodec<? extends MaterialCondition> codec() { return null; }
                }""");
        return sources;
    }

    /** The host as the addon's code was compiled against it: isBiome took only keys. */
    private static Map<String, String> withIsBiome() {
        Map<String, String> sources = new HashMap<>(HOST);
        sources.put("net/minecraft/world/level/levelgen/material/MaterialRules.java", """
                package net.minecraft.world.level.levelgen.material;
                import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
                public class MaterialRules {
                    public static MaterialCondition isBiome(net.minecraft.resources.ResourceKey<?>... keys) { throw new UnsupportedOperationException(); }
                }""");
        return sources;
    }

    /** 26.3's shape of the members the rebuilt key condition and Citadel's read touch. */
    private static final Map<String, String> HOST = Map.ofEntries(
            Map.entry("com/mojang/serialization/MapCodec.java", """
                    package com.mojang.serialization;
                    public abstract class MapCodec<A> { public static <A> MapCodec<A> unit(A value) { return null; } }"""),
            Map.entry("com/mojang/serialization/Codec.java", "package com.mojang.serialization; public interface Codec<A> {}"),
            Map.entry("net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {
                        private static final java.util.Map<String, ResourceKey<Object>> KEYS = new java.util.HashMap<>();
                        private final String id;
                        private ResourceKey(String id) { this.id = id; }
                        public static synchronized ResourceKey<Object> of(String id) { return KEYS.computeIfAbsent(id, ResourceKey::new); }
                        @Override public String toString() { return id; }
                    }"""),
            Map.entry("net/minecraft/core/HolderOwner.java", "package net.minecraft.core; public interface HolderOwner<T> {}"),
            Map.entry("net/minecraft/core/Holder.java", """
                    package net.minecraft.core;
                    import java.util.Optional;
                    import net.minecraft.resources.ResourceKey;
                    public interface Holder<T> {
                        T value();
                        boolean is(ResourceKey<T> key);
                        Optional<ResourceKey<T>> unwrapKey();
                        static <T> Holder<T> direct(T value) { throw new UnsupportedOperationException(); }
                        class Reference<T> implements Holder<T> {
                            private final ResourceKey<T> key;
                            private Reference(ResourceKey<T> key) { this.key = key; }
                            public static <T> Reference<T> createStandAlone(HolderOwner<T> owner, ResourceKey<T> key) { return new Reference<>(key); }
                            public T value() { throw new IllegalStateException("a stand-alone holder has no value"); }
                            public boolean is(ResourceKey<T> other) { return key == other; }
                            public Optional<ResourceKey<T>> unwrapKey() { return Optional.of(key); }
                        }
                    }"""),
            Map.entry("net/minecraft/core/HolderSet.java", """
                    package net.minecraft.core;
                    import java.util.List;
                    import java.util.stream.Stream;
                    public interface HolderSet<T> {
                        Stream<Holder<T>> stream();
                        static <T> Direct<T> direct(List<? extends Holder<T>> holders) { return new Direct<>(List.copyOf(holders)); }
                        final class Direct<T> implements HolderSet<T> {
                            private final List<Holder<T>> holders;
                            Direct(List<Holder<T>> holders) { this.holders = holders; }
                            public Stream<Holder<T>> stream() { return holders.stream(); }
                        }
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/placement/CaveSurface.java",
                    "package net.minecraft.world.level.levelgen.placement; public enum CaveSurface { CEILING, FLOOR }"),
            Map.entry("net/minecraft/world/level/levelgen/material/condition/ConditionEvaluator.java",
                    "package net.minecraft.world.level.levelgen.material.condition; public interface ConditionEvaluator { boolean test(); }"),
            Map.entry("net/minecraft/world/level/levelgen/material/condition/MaterialCondition.java", """
                    package net.minecraft.world.level.levelgen.material.condition;
                    public interface MaterialCondition {
                        ConditionEvaluator compile(net.minecraft.world.level.levelgen.material.MaterialRuleContext context);
                        com.mojang.serialization.MapCodec<? extends MaterialCondition> codec();
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/material/condition/BiomeCondition.java", """
                    package net.minecraft.world.level.levelgen.material.condition;
                    public record BiomeCondition(net.minecraft.core.HolderSet<Object> biomes) implements MaterialCondition {
                        public ConditionEvaluator compile(net.minecraft.world.level.levelgen.material.MaterialRuleContext c) { return null; }
                        public com.mojang.serialization.MapCodec<? extends MaterialCondition> codec() { return null; }
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/material/rule/MaterialRule.java",
                    "package net.minecraft.world.level.levelgen.material.rule; public interface MaterialRule {}"),
            Map.entry("net/minecraft/world/level/levelgen/material/rule/ConditionRule.java", """
                    package net.minecraft.world.level.levelgen.material.rule;
                    import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
                    public record ConditionRule(MaterialCondition ifTrue, MaterialRule thenRun) implements MaterialRule {}"""),
            Map.entry("net/minecraft/world/level/levelgen/material/MaterialRuleContext.java", """
                    package net.minecraft.world.level.levelgen.material;
                    import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
                    public final class MaterialRuleContext {
                        public java.util.Set<net.minecraft.core.Holder<Object>> possibleBiomes() { return null; }
                        public net.minecraft.core.Holder<Object> getBiome() { return null; }
                        public abstract static class LazyYCondition implements ConditionEvaluator {
                            protected final MaterialRuleContext context;
                            protected LazyYCondition(MaterialRuleContext context) { this.context = context; }
                            protected abstract boolean compute();
                            public boolean test() { return compute(); }
                        }
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/material/MaterialRules.java", """
                    package net.minecraft.world.level.levelgen.material;
                    import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
                    import net.minecraft.world.level.levelgen.placement.CaveSurface;
                    public class MaterialRules {
                        public static MaterialCondition stoneDepthCheck(int offset, boolean add, CaveSurface surface) { return null; }
                        public static MaterialCondition stoneDepthCheck(int offset, boolean add, int secondary, CaveSurface surface) { return null; }
                    }"""));

    /** Citadel's grouping: the first biome of each biome-conditioned rule, and a check that keeps 26.3's own type. */
    private static final Map<String, String> CITADEL = Map.of("citadel/Rules.java", """
            package citadel;
            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
            import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
            import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
            import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
            public final class Rules {
                public static List<String> firstBiomes(List<MaterialRule> rules) {
                    List<String> found = new ArrayList<>();
                    for (MaterialRule rule : rules) {
                        if (rule instanceof ConditionRule test) {
                            MaterialCondition condition = test.ifTrue();
                            if (condition instanceof BiomeCondition biome && !biome.biomes.isEmpty()) {
                                found.add(String.valueOf(biome.biomes.get(0)));
                            }
                        }
                    }
                    return found;
                }
                public static boolean isVanilla(Object condition) { return condition instanceof BiomeCondition; }
            }""");

    /** The addon: an old isBiome rule, and a rule over 26.3's own biome condition. */
    private static final Map<String, String> CAVES = Map.of("caves/Rules.java", """
            package caves;
            import java.util.List;
            import net.minecraft.core.Holder;
            import net.minecraft.core.HolderSet;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.levelgen.material.MaterialRules;
            import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
            import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
            import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
            public final class Rules {
                public static MaterialRule caveRule() {
                    return new ConditionRule(MaterialRules.isBiome(ResourceKey.of("alexscaves:primordial_caves")), new MaterialRule() {});
                }
                public static MaterialRule vanillaRule() {
                    Holder<Object> plains = Holder.Reference.createStandAlone(null, ResourceKey.of("minecraft:plains"));
                    return new ConditionRule(new BiomeCondition(HolderSet.direct(List.of(plains))), new MaterialRule() {});
                }
            }""");

    private static Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        Map<String, String> merged = new HashMap<>(first);
        merged.putAll(second);
        return merged;
    }
}
