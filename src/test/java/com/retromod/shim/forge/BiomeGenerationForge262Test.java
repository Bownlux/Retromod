/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.embedded.LegacyBiomeCarvers;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 gives biome modifiers the vanilla PlainBuilder; old builder calls and carver steps land on it. */
class BiomeGenerationForge262Test {

    private static final String FORGE_BUILDER = LegacyBiomeGenerationBridge.FORGE_BUILDER;
    private static final String PLAIN = LegacyBiomeGenerationBridge.PLAIN_BUILDER;
    private static final String BIOME_INFO_BUILDER = "net/minecraftforge/common/world/ModifiableBiomeInfo$BiomeInfo$Builder";
    private static final String DECORATION = "net/minecraft/world/level/levelgen/GenerationStep$Decoration";
    private static final String L_HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String MOD = "mod/Modifier";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path tempDir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Features go to the host builder, and both carver calls share its one carver list")
    void builderCallsReachTheHostBuilder() throws Exception {
        LegacyBiomeGenerationBridge.register(transformer);
        ClassLoader host = new ForgeHostStandIns()
                .add("net/minecraft/core/Holder.java", "package net.minecraft.core; public interface Holder<T> {}")
                .add("net/minecraft/world/level/levelgen/GenerationStep.java", """
                        package net.minecraft.world.level.levelgen;
                        public class GenerationStep { public enum Decoration { RAW_GENERATION } }""")
                .add("net/minecraft/world/level/biome/BiomeGenerationSettings.java", """
                        package net.minecraft.world.level.biome;
                        import java.util.ArrayList;
                        import java.util.List;
                        public class BiomeGenerationSettings {
                            public static class PlainBuilder {
                                protected final List<Object> carvers = new ArrayList<>();
                                public final List<Object> features = new ArrayList<>();
                                public PlainBuilder() {}
                                public List<Object> getFeatures(net.minecraft.world.level.levelgen.GenerationStep.Decoration step) {
                                    return features;
                                }
                                public PlainBuilder addCarver(net.minecraft.core.Holder<?> carver) {
                                    carvers.add(carver);
                                    return this;
                                }
                            }
                        }""")
                .add("net/minecraftforge/common/world/ModifiableBiomeInfo.java", """
                        package net.minecraftforge.common.world;
                        import net.minecraft.world.level.biome.BiomeGenerationSettings.PlainBuilder;
                        public class ModifiableBiomeInfo {
                            public static class BiomeInfo {
                                public static class Builder {
                                    public final PlainBuilder generation = new PlainBuilder();
                                    public PlainBuilder getGenerationSettings() { return generation; }
                                }
                            }
                        }""")
                .loader(tempDir, transformer, Map.of(MOD, transformer.transformClass(modifier(), MOD)));
        Class<?> plainType = host.loadClass("net.minecraft.world.level.biome.BiomeGenerationSettings$PlainBuilder");
        Class<?> holderType = host.loadClass("net.minecraft.core.Holder");
        Class<?> mod = host.loadClass("mod.Modifier");
        Object builder = plainType.getConstructor().newInstance();
        Object cave = java.lang.reflect.Proxy.newProxyInstance(host, new Class<?>[] {holderType}, (p, m, a) -> "cave");

        assertSame(builder, mod.getMethod("addAir", plainType, holderType).invoke(null, builder, cave),
                "addCarver returns the builder, so chained calls keep working");
        @SuppressWarnings("unchecked")
        List<Object> carvers = (List<Object>) mod.getMethod("liquidCarvers", plainType).invoke(null, builder);
        assertEquals(1, carvers.size(), "the liquid step reads the same list the air step wrote");
        carvers.clear();
        assertEquals(List.of(), readCarvers(plainType, builder), "a removal through the old list reaches the builder");
        assertSame(plainType.getField("features").get(builder), mod.getMethod("features", plainType).invoke(null, builder));

        // Forge 26.2's BiomeInfo.Builder hands out the vanilla builder under the old getter name.
        Class<?> infoType = host.loadClass("net.minecraftforge.common.world.ModifiableBiomeInfo$BiomeInfo$Builder");
        Object info = infoType.getConstructor().newInstance();
        assertSame(infoType.getField("generation").get(info), mod.getMethod("generation", infoType).invoke(null, info));
    }

    @Test
    @DisplayName("On a 26.3 host the BiomeInfo getter becomes generationSettings() returning the vanilla builder")
    void getterComposesWithThe263Rename() {
        LegacyBiomeGenerationBridge.register(transformer);
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new Forge_26_2_to_26_3().registerRedirects(transformer);
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(modifier(), MOD)).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call && call.owner.equals(BIOME_INFO_BUILDER)) {
                calls.add(call.name + call.desc);
            }
        }));
        assertEquals(List.of("generationSettings()L" + PLAIN + ";"), calls);
    }

    @Test
    @DisplayName("NeoForge and Forge 1.21.11 keep their own builder")
    void otherHostsKeepTheirBuilder() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyBiomeGenerationBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FORGE_BUILDER), "1.21.11");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        LegacyBiomeGenerationBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FORGE_BUILDER), "NeoForge");
    }

    @Test
    @DisplayName("A builder without a carver list fails with a message naming the builder")
    void missingListIsReported() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LegacyBiomeCarvers.carvers(new Object(), "AIR"));
        assertTrue(thrown.getMessage().contains("java.lang.Object"), thrown.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readCarvers(Class<?> plainType, Object builder) throws Exception {
        var field = plainType.getDeclaredField("carvers");
        field.setAccessible(true);
        return (List<Object>) field.get(builder);
    }

    /**
     * A Forge 1.20.1 biome modifier: it adds a carver to the air step, reads the liquid step,
     * reads features, and asks BiomeInfo.Builder for the generation builder. The removed carving
     * steps are passed as null here, since no step value survives on the host.
     */
    private static byte[] modifier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        String lBuilder = "L" + FORGE_BUILDER + ";";

        MethodVisitor add = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addAir", "(" + lBuilder + L_HOLDER + ")Ljava/lang/Object;", null, null);
        add.visitCode();
        add.visitVarInsn(ALOAD, 0);
        add.visitInsn(ACONST_NULL);
        add.visitVarInsn(ALOAD, 1);
        add.visitMethodInsn(INVOKEVIRTUAL, FORGE_BUILDER, "addCarver", LegacyBiomeGenerationBridge.ADD_CARVER, false);
        add.visitInsn(ARETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();

        MethodVisitor carvers = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "liquidCarvers", "(" + lBuilder + ")Ljava/util/List;", null, null);
        carvers.visitCode();
        carvers.visitVarInsn(ALOAD, 0);
        carvers.visitInsn(ACONST_NULL);
        carvers.visitMethodInsn(INVOKEVIRTUAL, FORGE_BUILDER, "getCarvers", LegacyBiomeGenerationBridge.GET_CARVERS, false);
        carvers.visitInsn(ARETURN);
        carvers.visitMaxs(0, 0);
        carvers.visitEnd();

        MethodVisitor features = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "features", "(" + lBuilder + ")Ljava/util/List;", null, null);
        features.visitCode();
        features.visitVarInsn(ALOAD, 0);
        features.visitInsn(ACONST_NULL);
        features.visitMethodInsn(INVOKEVIRTUAL, FORGE_BUILDER, "getFeatures", "(L" + DECORATION + ";)Ljava/util/List;", false);
        features.visitInsn(ARETURN);
        features.visitMaxs(0, 0);
        features.visitEnd();

        MethodVisitor info = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "generation", "(L" + BIOME_INFO_BUILDER + ";)Ljava/lang/Object;", null, null);
        info.visitCode();
        info.visitVarInsn(ALOAD, 0);
        info.visitMethodInsn(INVOKEVIRTUAL, BIOME_INFO_BUILDER, "getGenerationSettings", "()" + lBuilder, false);
        info.visitInsn(ARETURN);
        info.visitMaxs(0, 0);
        info.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
