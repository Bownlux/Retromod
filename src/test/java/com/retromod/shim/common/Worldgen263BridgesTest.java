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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Tree growers, carvers, tree and lake features, and flower beds written for 26.2 link against 26.3's rebuilt worldgen. */
class Worldgen263BridgesTest {

    private static final String ROOT = RebuiltApiClasses.ROOT;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new TreeGrower(name, tree, mega, flowers) becomes the rebuilt factory on 26.3 and stays on 26.2")
    void treeGrowerConstructionFollowsTheHost(@TempDir Path dir) throws Exception {
        assertEquals(List.of(ROOT + "minecraft/world/level/block/grower/LegacyTreeGrowers.create"),
                factoryCalls(transformAll(dir, "26.3").get("mod/Growers"), "grower"));
        assertEquals(List.of(), factoryCalls(transformAll(dir, "26.2").get("mod/Growers"), "grower"),
                "26.2 still has the old constructor");
    }

    @Test
    @DisplayName("A grower with only a secondary tree grows nothing on the rolls the secondary loses, as before")
    void secondaryOnlyGrowerKeepsItsEmptyRolls(@TempDir Path dir) throws Throwable {
        transformAll(dir, "26.3");
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), HOST_263), Map.of());
        Object key = run.call("net/minecraft/resources/ResourceKey", "of", "minecraft:feature", "bop:fir");
        Object grower = run.call(ROOT + "minecraft/world/level/block/grower/LegacyTreeGrowers", "create", "fir", 0.1F,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(key),
                java.util.Optional.empty(), java.util.Optional.empty());
        assertEquals("[minecraft:feature/retromod:legacy_tree_grower_no_tree=900, minecraft:feature/bop:fir=100]",
                grower.toString(), "the main tree's 90 percent names a feature that does not exist");
    }

    @Test
    @DisplayName("An old cave carver becomes an inert carver type, and its configuration codec a stand-in")
    void carverMovesOntoTheInertType(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = transformAll(dir, "26.3");
        assertEquals(ROOT + "minecraft/world/level/levelgen/carver/LegacyCarverType", superName(mod.get("mod/OriginCarver")));
        assertEquals(List.of(ROOT + "minecraft/world/level/levelgen/carver/LegacyCarverConfiguration.CODEC:Lcom/mojang/serialization/Codec;"),
                BridgeHarness.fields(mod.get("mod/OriginCarver"), "make"));
    }

    @Test
    @DisplayName("Tree, lake and coral subclasses move onto rebuilt bases, and their configurations onto rebuilt ones")
    void vanillaFeatureSubclassesMove(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = transformAll(dir, "26.3");
        assertEquals(ROOT + "minecraft/world/level/levelgen/feature/LegacyTreeFeature", superName(mod.get("mod/MyTree")));
        assertEquals(LegacyFeatureBridge.LEGACY_FEATURE, superName(mod.get("mod/MyLake")));
        assertEquals(LegacyFeatureBridge.LEGACY_FEATURE, superName(mod.get("mod/MyCoral")));
        String treeConfig = ROOT + "minecraft/world/level/levelgen/feature/configurations/LegacyTreeConfiguration";
        assertTrue(method(mod.get("mod/MyTree"), "doPlace").desc.endsWith("L" + treeConfig + ";)Z"),
                "doPlace keeps overriding the rebuilt base's hook");
        assertEquals(List.of(treeConfig + ".ignoreVines:Z"), BridgeHarness.fields(mod.get("mod/MyTree"), "doPlace"));
    }

    @Test
    @DisplayName("new FlowerBedBlock(props) becomes the generated factory, which passes the old shape height")
    void flowerBedConstruction(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = transformAll(dir, "26.3");
        assertTrue(BridgeHarness.calls(mod.get("mod/Beds"), "bed").contains(LegacyFlowerBedBlockBridge.INTERNAL + ".create"
                + LegacyFlowerBedBlockBridge.FACTORY_DESC));
    }

    @Test
    @DisplayName("Each bridge registers only where the host made the change; offline the target decides")
    void gatesFollowTheHost() {
        ClassNode carverInterface = new ClassNode();
        carverInterface.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        assertTrue(LegacyCarverBridge.hostRebuiltIt(carverInterface));
        assertFalse(LegacyCarverBridge.hostRebuiltIt(new ClassNode()), "26.2's abstract class");

        ClassNode oldBed = new ClassNode();
        oldBed.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", LegacyFlowerBedBlockBridge.LEGACY_CTOR, null, null));
        assertFalse(LegacyFlowerBedBlockBridge.hostDroppedIt(oldBed));
        assertTrue(LegacyFlowerBedBlockBridge.hostDroppedIt(new ClassNode()));

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(LegacyCarverBridge.hostRebuiltIt(null));
        assertFalse(LegacyFlowerBedBlockBridge.hostDroppedIt(null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyCarverBridge.hostRebuiltIt(null));
        assertTrue(LegacyFlowerBedBlockBridge.hostDroppedIt(null));
    }

    /** The mod compiled against 26.2's shapes and transformed for {@code target} with no host jar, so the target decides. */
    private Map<String, byte[]> transformAll(Path dir, String target) throws Exception {
        Consumer<RetromodTransformer> register = t -> {
            t.setModSourceVersion("1.21.1");
            LegacyFeatureBridge.register(t);
            LegacyTreeGrowerConstructorBridge.register(t, name -> null);
            LegacyCarverBridge.register(t, name -> null);
            LegacyVanillaFeatureBridge.register(t);
            LegacyFlowerBedBlockBridge.register(t, name -> null);
        };
        Path compiled = dir.resolve(target);
        return BridgeHarness.transform(target, register,
                BridgeHarness.only(BridgeHarness.compile(compiled, OLD_262), "mod/"));
    }

    private static List<String> factoryCalls(byte[] bytes, String method) {
        List<String> calls = new ArrayList<>();
        for (String call : BridgeHarness.calls(bytes, method)) {
            if (call.startsWith("com/retromod/")) calls.add(call.substring(0, call.indexOf('(')));
        }
        return calls;
    }

    private static String superName(byte[] bytes) {
        return new ClassReader(bytes).getSuperName();
    }

    private static MethodNode method(byte[] bytes, String name) {
        return BridgeHarness.method(bytes, name);
    }

    /** 26.3's weighted lists, keys and grower, recording what the factory builds. */
    private static final Map<String, String> HOST_263 = Map.of(
            "net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    public interface Registry<T> {}""",
            "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public record Identifier(String namespace, String path) {
                        public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                        @Override public String toString() { return namespace + ":" + path; }
                    }""",
            "net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    import net.minecraft.core.Registry;
                    public final class ResourceKey<T> {
                        private final String registry;
                        private final Identifier id;
                        private ResourceKey(String registry, Identifier id) { this.registry = registry; this.id = id; }
                        public static ResourceKey<?> of(String registry, String id) {
                            String[] parts = id.split(":");
                            return new ResourceKey<>(registry, Identifier.fromNamespaceAndPath(parts[0], parts[1]));
                        }
                        public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, Identifier id) {
                            return new ResourceKey<>(registry.registry, id);
                        }
                        public ResourceKey<Registry<T>> registryKey() { return new ResourceKey<>(registry, null); }
                        public Identifier identifier() { return id; }
                        @Override public String toString() { return registry + "/" + id; }
                    }""",
            "net/minecraft/util/random/WeightedList.java", """
                    package net.minecraft.util.random;
                    import java.util.ArrayList;
                    import java.util.List;
                    public final class WeightedList<E> {
                        private final List<String> entries;
                        private WeightedList(List<String> entries) { this.entries = entries; }
                        public static <E> Builder<E> builder() { return new Builder<>(); }
                        @Override public String toString() { return entries.toString(); }
                        public static final class Builder<E> {
                            private final List<String> entries = new ArrayList<>();
                            public Builder<E> add(E value, int weight) { entries.add(value + "=" + weight); return this; }
                            public WeightedList<E> build() { return new WeightedList<>(entries); }
                        }
                    }""",
            "net/minecraft/world/level/block/grower/TreeGrower.java", """
                    package net.minecraft.world.level.block.grower;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.util.random.WeightedList;
                    public final class TreeGrower {
                        private final WeightedList<?> trees;
                        public TreeGrower(String name, WeightedList trees, WeightedList megaTrees, WeightedList flowerTrees, ResourceKey shortest) {
                            this.trees = trees;
                        }
                        @Override public String toString() { return trees.toString(); }
                    }""");

    /** 26.2's worldgen and block shapes, and mod classes written like Biomes O' Plenty's. */
    private static final Map<String, String> OLD_262 = Map.ofEntries(
            Map.entry("com/mojang/serialization/Codec.java", """
                    package com.mojang.serialization;
                    public interface Codec<A> {}"""),
            Map.entry("net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public class ResourceKey<T> {}"""),
            Map.entry("net/minecraft/world/level/block/grower/TreeGrower.java", """
                    package net.minecraft.world.level.block.grower;
                    import java.util.Optional;
                    import net.minecraft.resources.ResourceKey;
                    public final class TreeGrower {
                        public TreeGrower(String name, Optional<ResourceKey<?>> mega, Optional<ResourceKey<?>> tree, Optional<ResourceKey<?>> flowers) {}
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/carver/CarverConfiguration.java", """
                    package net.minecraft.world.level.levelgen.carver;
                    public class CarverConfiguration {}"""),
            Map.entry("net/minecraft/world/level/levelgen/carver/CaveCarverConfiguration.java", """
                    package net.minecraft.world.level.levelgen.carver;
                    import com.mojang.serialization.Codec;
                    public class CaveCarverConfiguration extends CarverConfiguration {
                        public static final Codec<CaveCarverConfiguration> CODEC = null;
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/carver/WorldCarver.java", """
                    package net.minecraft.world.level.levelgen.carver;
                    import com.mojang.serialization.Codec;
                    public abstract class WorldCarver<C extends CarverConfiguration> {
                        public WorldCarver(Codec<C> codec) {}
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/carver/CaveWorldCarver.java", """
                    package net.minecraft.world.level.levelgen.carver;
                    import com.mojang.serialization.Codec;
                    public class CaveWorldCarver extends WorldCarver<CaveCarverConfiguration> {
                        public CaveWorldCarver(Codec<CaveCarverConfiguration> codec) { super(codec); }
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/feature/Feature.java", """
                    package net.minecraft.world.level.levelgen.feature;
                    import com.mojang.serialization.Codec;
                    public abstract class Feature<C> {
                        public Feature(Codec<C> codec) {}
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/feature/configurations/TreeConfiguration.java", """
                    package net.minecraft.world.level.levelgen.feature.configurations;
                    public class TreeConfiguration {
                        public final boolean ignoreVines;
                        public TreeConfiguration(boolean ignoreVines) { this.ignoreVines = ignoreVines; }
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/feature/TreeFeature.java", """
                    package net.minecraft.world.level.levelgen.feature;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
                    public class TreeFeature extends Feature<TreeConfiguration> {
                        public TreeFeature(Codec<TreeConfiguration> codec) { super(codec); }
                        protected boolean doPlace(Object level, TreeConfiguration config) { return false; }
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/feature/LakeFeature.java", """
                    package net.minecraft.world.level.levelgen.feature;
                    import com.mojang.serialization.Codec;
                    public class LakeFeature extends Feature<LakeFeature.Configuration> {
                        public LakeFeature(Codec<Configuration> codec) { super(codec); }
                        public record Configuration(Object fluid, Object barrier) {}
                    }"""),
            Map.entry("net/minecraft/world/level/levelgen/feature/CoralTreeFeature.java", """
                    package net.minecraft.world.level.levelgen.feature;
                    import com.mojang.serialization.Codec;
                    public class CoralTreeFeature extends Feature<Object> {
                        public CoralTreeFeature(Codec<Object> codec) { super(codec); }
                    }"""),
            Map.entry("net/minecraft/world/level/block/state/BlockBehaviour.java", """
                    package net.minecraft.world.level.block.state;
                    public class BlockBehaviour { public static class Properties {} }"""),
            Map.entry("net/minecraft/world/level/block/FlowerBedBlock.java", """
                    package net.minecraft.world.level.block;
                    import net.minecraft.world.level.block.state.BlockBehaviour;
                    public class FlowerBedBlock { public FlowerBedBlock(BlockBehaviour.Properties properties) {} }"""),
            Map.entry("mod/Growers.java", """
                    package mod;
                    import java.util.Optional;
                    import net.minecraft.world.level.block.grower.TreeGrower;
                    public class Growers {
                        public static Object grower() { return new TreeGrower("fir", Optional.empty(), Optional.empty(), Optional.empty()); }
                    }"""),
            Map.entry("mod/OriginCarver.java", """
                    package mod;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
                    import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
                    public class OriginCarver extends CaveWorldCarver {
                        OriginCarver(Codec<CaveCarverConfiguration> codec) { super(codec); }
                        public static Object make() { return new OriginCarver(CaveCarverConfiguration.CODEC); }
                    }"""),
            Map.entry("mod/MyTree.java", """
                    package mod;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.world.level.levelgen.feature.TreeFeature;
                    import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
                    public class MyTree extends TreeFeature {
                        MyTree(Codec<TreeConfiguration> codec) { super(codec); }
                        @Override protected boolean doPlace(Object level, TreeConfiguration config) { return config.ignoreVines; }
                    }"""),
            Map.entry("mod/MyLake.java", """
                    package mod;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.world.level.levelgen.feature.LakeFeature;
                    public class MyLake extends LakeFeature {
                        MyLake(Codec<LakeFeature.Configuration> codec) { super(codec); }
                    }"""),
            Map.entry("mod/MyCoral.java", """
                    package mod;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.world.level.levelgen.feature.CoralTreeFeature;
                    public class MyCoral extends CoralTreeFeature {
                        MyCoral(Codec<Object> codec) { super(codec); }
                    }"""),
            Map.entry("mod/Beds.java", """
                    package mod;
                    import net.minecraft.world.level.block.FlowerBedBlock;
                    import net.minecraft.world.level.block.state.BlockBehaviour;
                    public class Beds {
                        public static Object bed() { return new FlowerBedBlock(new BlockBehaviour.Properties()); }
                    }"""));
}
