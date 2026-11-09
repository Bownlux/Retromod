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

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A mod's own biome source answers 26.3's resolver from its old lookup, and the old sampler call keeps one sampler per state. */
class LegacyBiomeSourceBridgeTest {

    private static final String REBUILT = LegacyWorldgenMembersBridge.LEGACY_BIOME_SOURCE;
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old source's lookup answers the resolver 26.3 asks it for, with the sampler the resolver was built for")
    void resolverReachesTheOldLookup(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), oldSources()), "mod/");
        Map<String, byte[]> out = BridgeHarness.transform("26.3",
                t -> LegacyWorldgenMembersBridge.register263(t, false, true, false), mod);
        assertEquals(REBUILT, new ClassReader(out.get("mod/FloatingIslands")).getSuperName());

        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("new"), newSources()), out);
        Class<?> samplerType = run.loadClass("net.minecraft.world.level.biome.Climate$Sampler");
        Object sampler = samplerType.getConstructor().newInstance();
        Object source = run.loadClass("mod.FloatingIslands").getConstructor().newInstance();
        Object resolver = run.loadClass("net.minecraft.world.level.biome.BiomeSource").getMethod("createResolver", samplerType)
                .invoke(source, sampler);
        run.loadClass("net.minecraft.world.level.biome.BiomeResolver").getMethod("getNoiseBiome", int.class, int.class, int.class)
                .invoke(resolver, 1, 2, 3);
        assertSame(sampler, run.loadClass("mod.FloatingIslands").getField("sampler").get(null));
        assertEquals("1 2 3", run.loadClass("mod.FloatingIslands").getField("position").get(null));
    }

    @Test
    @DisplayName("Where the codec bridge moves sources onto its own base, that base sits on the rebuilt source on 26.3")
    void codecBaseSitsOnTheRebuiltSource(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), oldSources()), "mod/");
        Map<String, byte[]> out = BridgeHarness.transform("26.3", t -> {
            LegacyCodecTypeBridge.registerCodecBases(t);
            LegacyWorldgenMembersBridge.register263(t, false, true, false);
        }, mod);
        String codecBase = LegacyWorldgenMembersBridge.CODEC_BIOME_SOURCE;
        assertEquals(codecBase, new ClassReader(out.get("mod/FloatingIslands")).getSuperName());
        byte[] generated = RetromodTransformer.getInstance().getSyntheticClasses().get(codecBase);
        assertEquals(REBUILT, new ClassReader(generated).getSuperName());
    }

    @Test
    @DisplayName("On a host that keeps the old lookup, the codec bridge's base still extends BiomeSource")
    void codecBaseStaysOnBiomeSourceBefore263() {
        BridgeHarness.transform("26.2", LegacyCodecTypeBridge::registerCodecBases, Map.of());
        byte[] generated = RetromodTransformer.getInstance().getSyntheticClasses().get(LegacyWorldgenMembersBridge.CODEC_BIOME_SOURCE);
        assertEquals(LegacyWorldgenMembersBridge.BIOME_SOURCE, new ClassReader(generated).getSuperName());
    }

    @Test
    @DisplayName("The old sampler call builds one climate sampler per random state")
    void samplerIsKeptPerState(@TempDir Path dir) throws Throwable {
        BridgeHarness.transform("26.3", t -> LegacyWorldgenMembersBridge.register263(t, true, false, false), Map.of());
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("new"), newSources()), Map.of());
        Class<?> stateType = run.loadClass("net.minecraft.world.level.levelgen.RandomState");
        Object overworld = stateType.getConstructor().newInstance();
        Object nether = stateType.getConstructor().newInstance();
        // Looked up by its exact type, so the members class's other methods need no stand-ins.
        java.lang.invoke.MethodHandle sampler = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                run.loadClass(LegacyWorldgenMembersBridge.MEMBERS.replace('/', '.')), "sampler",
                java.lang.invoke.MethodType.methodType(run.loadClass("net.minecraft.world.level.biome.Climate$Sampler"), stateType));
        Object first = sampler.invoke(overworld);
        assertSame(first, sampler.invoke(overworld));
        assertNotSame(first, sampler.invoke(nether));
        assertSame(first, sampler.invoke(overworld));
        assertEquals(2, stateType.getField("built").getInt(null));
    }

    // 1.21.1's shape: the source implements the lookup itself.
    private static Map<String, String> oldSources() {
        return Map.of(
                "net/minecraft/core/Holder.java", "package net.minecraft.core; public interface Holder<T> {}",
                "net/minecraft/world/level/biome/Climate.java",
                "package net.minecraft.world.level.biome; public class Climate { public static class Sampler {} }",
                "net/minecraft/world/level/biome/BiomeSource.java", """
                        package net.minecraft.world.level.biome;
                        public abstract class BiomeSource {
                            public abstract net.minecraft.core.Holder<?> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler);
                        }
                        """,
                "mod/FloatingIslands.java", """
                        package mod;
                        import net.minecraft.world.level.biome.Climate;
                        public class FloatingIslands extends net.minecraft.world.level.biome.BiomeSource {
                            public static Object sampler;
                            public static String position;
                            public net.minecraft.core.Holder<?> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
                                FloatingIslands.sampler = sampler;
                                position = x + " " + y + " " + z;
                                return null;
                            }
                        }
                        """);
    }

    // 26.3's shape: the source builds a resolver, and a random state builds a sampler for each context.
    private static Map<String, String> newSources() {
        return Map.of(
                "net/minecraft/core/Holder.java", "package net.minecraft.core; public interface Holder<T> {}",
                "net/minecraft/world/level/biome/Climate.java",
                "package net.minecraft.world.level.biome; public class Climate { public static class Sampler { public Sampler() {} } }",
                "net/minecraft/world/level/biome/BiomeResolver.java",
                "package net.minecraft.world.level.biome; public interface BiomeResolver { net.minecraft.core.Holder<?> getNoiseBiome(int x, int y, int z); }",
                "net/minecraft/world/level/biome/BiomeSource.java", """
                        package net.minecraft.world.level.biome;
                        public abstract class BiomeSource {
                            protected BiomeSource() {}
                            public abstract BiomeResolver createResolver(Climate.Sampler sampler);
                        }
                        """,
                "net/minecraft/world/level/levelgen/densityfunction/SamplerContext.java", """
                        package net.minecraft.world.level.levelgen.densityfunction;
                        public class SamplerContext { public static final SamplerContext EMPTY_UNCACHED = new SamplerContext(); }
                        """,
                "net/minecraft/world/level/levelgen/RandomState.java", """
                        package net.minecraft.world.level.levelgen;
                        import net.minecraft.world.level.biome.Climate;
                        import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
                        public final class RandomState {
                            public static int built;
                            public Climate.Sampler createClimateSampler(SamplerContext context) {
                                built++;
                                return new Climate.Sampler();
                            }
                        }
                        """);
    }
}
