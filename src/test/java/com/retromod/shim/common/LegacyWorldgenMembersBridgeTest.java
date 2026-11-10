/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old worldgen and villager calls and constants reach their 26.3 forms (YUNG's API, Moonlight, Biomes O' Plenty). */
class LegacyWorldgenMembersBridgeTest {

    private static final String MEMBERS = LegacyWorldgenMembersBridge.MEMBERS;
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String SAMPLER = "Lnet/minecraft/world/level/biome/Climate$Sampler;";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    void chunkSetBlockStateWithAMovedFlagCallsTheRebuiltOne() {
        LegacyWorldgenMembersBridge.registerOlder(transformer, true, false, false);

        List<MethodInsnNode> calls = calls(transform(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "setBlockState",
                    "(" + POS + STATE + "Z)" + STATE, false);
            m.visitInsn(Opcodes.POP);
            // A call already built with update flags is left alone.
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ICONST_3);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "setBlockState",
                    "(" + POS + STATE + "I)" + STATE, false);
            m.visitInsn(Opcodes.POP);
        }));

        assertEquals(MEMBERS + ".setBlockState(Lnet/minecraft/world/level/chunk/ChunkAccess;" + POS + STATE + "Z)" + STATE,
                describe(calls.get(0)));
        assertEquals(Opcodes.INVOKESTATIC, calls.get(0).getOpcode());
        assertEquals("net/minecraft/world/level/chunk/LevelChunk.setBlockState(" + POS + STATE + "I)" + STATE,
                describe(calls.get(1)), "the int-flag call is already current");
    }

    @Test
    void spawnEntryCodecAndVillagerTypeConstantsReadTheirNewForms() {
        // The chunk redirects come along, as on a real host, so the transformer has a redirect to run.
        LegacyWorldgenMembersBridge.registerOlder(transformer, true, true, true);

        MethodNode body = transformMethod(m -> {
            m.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/level/biome/MobSpawnSettings$SpawnerData", "CODEC",
                    "Lcom/mojang/serialization/Codec;");
            m.visitInsn(Opcodes.POP);
            m.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/npc/villager/VillagerType", "PLAINS",
                    "Lnet/minecraft/world/entity/npc/villager/VillagerType;");
            m.visitInsn(Opcodes.POP);
        });

        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : body.instructions) {
            if (insn instanceof FieldInsnNode field) reads.add(field.name + ":" + field.desc);
            if (insn instanceof MethodInsnNode call) reads.add(describe(call));
        }
        assertEquals(List.of("CODEC:Lcom/mojang/serialization/MapCodec;",
                MEMBERS + ".codecOf(Lcom/mojang/serialization/MapCodec;)Lcom/mojang/serialization/Codec;",
                "PLAINS:Lnet/minecraft/resources/ResourceKey;",
                MEMBERS + ".villagerType(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/entity/npc/villager/VillagerType;"),
                reads, "Moonlight read SpawnerData.CODEC as a codec, and mods read villager types as types");
    }

    @Test
    void samplerAndVanillaBiomeLookupsGoThroughTheRebuiltHelpers() {
        LegacyWorldgenMembersBridge.register263(transformer, true, true, false);

        List<MethodInsnNode> calls = calls(transform(m -> {
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/RandomState", "sampler",
                    "()" + SAMPLER, false);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/biome/MultiNoiseBiomeSource",
                    "getNoiseBiome", "(III" + SAMPLER + ")" + HOLDER, false);
            m.visitInsn(Opcodes.POP);
            // A mod's own source still declares the old method, so its call stays.
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "mod/ModBiomeSource", "getNoiseBiome",
                    "(III" + SAMPLER + ")" + HOLDER, false);
            m.visitInsn(Opcodes.POP);
        }));

        assertEquals(MEMBERS + ".sampler(Lnet/minecraft/world/level/levelgen/RandomState;)" + SAMPLER, describe(calls.get(0)));
        assertEquals(MEMBERS + ".getNoiseBiome(Lnet/minecraft/world/level/biome/BiomeSource;III" + SAMPLER + ")" + HOLDER,
                describe(calls.get(1)));
        assertEquals("mod/ModBiomeSource.getNoiseBiome(III" + SAMPLER + ")" + HOLDER, describe(calls.get(2)));
    }

    @Test
    void configuredFeaturePlaceBecomesTheFeatureInterfaceCall() throws Exception {
        // A transformer with no host index, which another test may have left on the shared one, so 26.3's table
        // decides that Feature is an interface.
        java.lang.reflect.Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer fresh = constructor.newInstance();
        LegacyWorldgenMembersBridge.register263(fresh, false, false, true);
        fresh.setModSourceVersion("1.21.1");
        // Any method redirect, as every real chain has: with none, method bodies are only renamed.
        fresh.registerMethodRedirect("test/kind/Old", "run", "()V", "test/kind/New", "run", "()V");

        MethodNode body = transform(fresh, m -> {
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/level/levelgen/feature/ConfiguredFeature");
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/feature/ConfiguredFeature", "place",
                    "(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;"
                            + "Lnet/minecraft/util/RandomSource;" + POS + ")Z", false);
            m.visitInsn(Opcodes.POP);
        });

        String feature = "net/minecraft/world/level/levelgen/feature/Feature";
        TypeInsnNode cast = first(body, TypeInsnNode.class);
        MethodInsnNode place = first(body, MethodInsnNode.class);
        assertEquals(feature, cast.desc, "26.3's feature is the configured feature");
        assertEquals(feature, place.owner, "with the same place method");
        // 26.3's Feature is an interface, so the old class call must become an interface call to link.
        assertEquals(Opcodes.INVOKEINTERFACE, place.getOpcode(), "the place call is an interface call");
        assertTrue(place.itf, "and names an interface method");
    }

    @Test
    void aHostWithTheOldShapesTakesNothing() {
        LegacyWorldgenMembersBridge.registerOlder(transformer, false, false, false);
        LegacyWorldgenMembersBridge.register263(transformer, false, false, false);

        List<MethodInsnNode> calls = calls(transform(m -> {
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/RandomState", "sampler",
                    "()" + SAMPLER, false);
            m.visitInsn(Opcodes.POP);
        }));

        assertEquals("net/minecraft/world/level/levelgen/RandomState.sampler()" + SAMPLER, describe(calls.get(0)));
    }

    // ---- fixtures ----

    private ClassNode transform(Consumer<MethodVisitor> code) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/WorldgenUser", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Lnet/minecraft/world/level/chunk/LevelChunk;)V", null, null);
        m.visitCode();
        code.accept(m);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/WorldgenUser")).accept(node, 0);
        return node;
    }

    private MethodNode transformMethod(Consumer<MethodVisitor> code) {
        return transform(code).methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
    }

    private MethodNode transform(RetromodTransformer with, Consumer<MethodVisitor> code) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/WorldgenUser", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Lnet/minecraft/world/level/chunk/LevelChunk;)V", null, null);
        m.visitCode();
        code.accept(m);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(with.transformClass(cw.toByteArray(), "mod/WorldgenUser")).accept(node, 0);
        return node.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    private static String describe(MethodInsnNode call) {
        return call.owner + "." + call.name + call.desc;
    }
}
