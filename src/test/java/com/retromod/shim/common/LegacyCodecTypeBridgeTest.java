/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.common.LegacyCodecTypeBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mod's structure type lambda and placement modifier against the 1.20.5 MapCodec types. */
class LegacyCodecTypeBridgeTest {

    private static final String STRUCTURE_TYPE = "net/minecraft/world/level/levelgen/structure/StructureType";
    private static final String STRUCTURE_PLACEMENT_TYPE =
            "net/minecraft/world/level/levelgen/structure/placement/StructurePlacementType";
    private static final String STRUCTURE_POOL_ELEMENT_TYPE =
            "net/minecraft/world/level/levelgen/structure/pools/StructurePoolElementType";

    private final String previousTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    void aCodecLambdaIsBuiltAsASupplierAndWrapped() {
        ClassNode node = node(transform());

        InvokeDynamicInsnNode lambda = null;
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, "structureType").instructions.toArray()) {
            if (insn instanceof InvokeDynamicInsnNode indy) lambda = indy;
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }

        assertNotNull(lambda);
        assertEquals("get", lambda.name, "the lambda is built as a Supplier");
        assertTrue(calls.contains(HELPER + "." + wrapName(STRUCTURE_TYPE)),
                "the supplier is wrapped into the structure type: " + calls);
    }

    @Test
    void placementModifierRegistrationGoesThroughTheHelper() {
        ClassNode node = node(transform());

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, "placement").instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }

        assertTrue(calls.contains(HELPER + ".registerPlacementModifier"), calls.toString());
    }

    @Test
    void theWrapperImplementsEveryBridgedInterfaceAndVerifies() throws Exception {
        ClassNode helper = node(generateHelper(INTERFACES));

        assertEquals(INTERFACES, helper.interfaces);
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void offline263BridgesTheTypesThatKeptTheirMapCodec() {
        RetromodTransformer transformer = registerOffline("26.3");

        assertEquals(List.of(STRUCTURE_TYPE, STRUCTURE_POOL_ELEMENT_TYPE), helperInterfaces(transformer),
                "26.3 keeps a map codec on StructureType and StructurePoolElementType and removed the placement types");
        assertFalse(redirectsPlacementModifierRegister(transformer),
                "26.3 has no PlacementModifierType, so its register call must not be redirected");
        assertEquals(CODEC_BASES[0][1], superAfterTransform(transformer, CODEC_BASES[0][0]),
                "a ChunkGenerator subclass is rebased on 26.3");
        assertEquals(CODEC_BASES[1][1], superAfterTransform(transformer, CODEC_BASES[1][0]),
                "a BiomeSource subclass is rebased on 26.3");
    }

    @Test
    void offline262KeepsThePlacementTypes() {
        RetromodTransformer transformer = registerOffline("26.2");

        assertEquals(List.of(PLACEMENT_MODIFIER_TYPE, STRUCTURE_TYPE, STRUCTURE_PLACEMENT_TYPE,
                        STRUCTURE_POOL_ELEMENT_TYPE), helperInterfaces(transformer),
                "26.2 took the map codec from StructureProcessorType only");
        assertTrue(redirectsPlacementModifierRegister(transformer),
                "26.2 still has PlacementModifierType.register, which takes a map codec");
    }

    @Test
    void theHostVersionDecidesPerTypeWhenTheClassCannotBeRead() {
        List<String> every = new ArrayList<>(INTERFACES);
        for (String[] base : CODEC_BASES) every.add(base[0]);
        assertEquals(7, every.size());

        for (String type : every) {
            assertFalse(mapCodecByVersion(type, "1.20.4"), type + " returned a plain codec on 1.20.4");
            assertTrue(mapCodecByVersion(type, "26.1"), type + " returns a map codec on 26.1");
        }
        assertFalse(mapCodecByVersion(PLACEMENT_MODIFIER_TYPE, "26.3-rc-1"),
                "a 26.3 release candidate already lacks PlacementModifierType");
        assertTrue(mapCodecByVersion(STRUCTURE_TYPE, "26.3-rc-1"),
                "StructureType keeps its map codec on a 26.3 release candidate");
    }

    /** Registers the bridge for {@code host} with no Minecraft class readable, as the CLI and AOT run. */
    private static RetromodTransformer registerOffline(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyCodecTypeBridge.register(transformer);
        return transformer;
    }

    private static List<String> helperInterfaces(RetromodTransformer transformer) {
        byte[] helper = transformer.getSyntheticClasses().get(HELPER);
        assertNotNull(helper, "the codec type helper was not registered");
        return node(helper).interfaces;
    }

    private static boolean redirectsPlacementModifierRegister(RetromodTransformer transformer) {
        return transformer.getMethodRedirects().keySet().stream()
                .anyMatch(key -> key.owner().equals(PLACEMENT_MODIFIER_TYPE) && key.name().equals("register"));
    }

    /** The superclass a mod class extending {@code base} has after the transform. */
    private static String superAfterTransform(RetromodTransformer transformer, String base) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/CustomWorldgen", null, base, null);
        cw.visitEnd();
        return node(transformer.transformClass(cw.toByteArray(), "mod/CustomWorldgen")).superName;
    }

    private static byte[] transform() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, INTERFACES);
        return transformer.transformClass(modStructures(), "test/ModStructures.class");
    }

    /** {@code StructureType<?> t = () -> CODEC;} and {@code PlacementModifierType.register(name, CODEC)}. */
    private static byte[] modStructures() {
        String codec = "L" + CODEC + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModStructures", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CODEC", codec, null, null).visitEnd();

        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$codec$0",
                "()" + codec, null, null);
        body.visitCode();
        body.visitFieldInsn(Opcodes.GETSTATIC, "test/ModStructures", "CODEC", codec);
        body.visitInsn(Opcodes.ARETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "structureType",
                "()L" + STRUCTURE_TYPE + ";", null, null);
        mv.visitCode();
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("codec", "()L" + STRUCTURE_TYPE + ";", metafactory,
                Type.getType("()" + codec),
                new Handle(Opcodes.H_INVOKESTATIC, "test/ModStructures", "lambda$codec$0", "()" + codec, false),
                Type.getType("()" + codec));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor placement = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "placement",
                "()Ljava/lang/Object;", null, null);
        placement.visitCode();
        placement.visitLdcInsn("always");
        placement.visitFieldInsn(Opcodes.GETSTATIC, "test/ModStructures", "CODEC", codec);
        placement.visitMethodInsn(Opcodes.INVOKESTATIC, PLACEMENT_MODIFIER_TYPE, "register",
                "(Ljava/lang/String;" + codec + ")L" + PLACEMENT_MODIFIER_TYPE + ";", true);
        placement.visitInsn(Opcodes.ARETURN);
        placement.visitMaxs(0, 0);
        placement.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /**
     * A mixin must extend a class in its target's hierarchy, so one that extends ChunkGenerator to
     * reach its members goes back to it after the rebase (C2ME's MixinNoiseChunkGenerator on 26.3).
     */
    @Test
    void aMixinOnAChunkGeneratorKeepsTheHostBase() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        registerCodecBases(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/mixin/MixinNoiseChunkGenerator", null,
                CODEC_BASES[0][0], null);
        var mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator"));
        targets.visitEnd();
        mixin.visitEnd();
        cw.visitEnd();
        byte[] rebased = transformer.transformClass(cw.toByteArray(), "mod/mixin/MixinNoiseChunkGenerator");
        byte[] repaired = new com.retromod.mixin.MixinCompatibilityTransformer(transformer)
                .applyPostRemapRepairs(rebased);

        assertEquals(CODEC_BASES[0][0], node(repaired).superName,
                "the generated base lives in the mod jar, outside NoiseBasedChunkGenerator's hierarchy");
    }

    /**
     * #372: Fossils and Archaeology 9.3.4.0's chunk generators override the 1.20.1 codec() that
     * returned a Codec, and register that codec, which NeoForge 1.21.1 could not dispatch.
     */
    @Test
    void chunkGeneratorsLandOnTheMapCodecBase() {
        String previous = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.21.1";
            transformer.clearRedirectsForTesting();
            registerCodecBases(transformer);

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/LairGenerator", null,
                    CODEC_BASES[0][0], null);
            cw.visitEnd();
            ClassNode out = new ClassNode();
            new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/LairGenerator")).accept(out, 0);

            assertEquals(CODEC_BASES[0][1], out.superName);
            for (String[] base : CODEC_BASES) {
                byte[] generated = generateCodecBase(base[1], base[0], java.util.Arrays.copyOfRange(base, 2, base.length));
                new ClassReader(generated).accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), true), 0);
            }
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = previous;
            transformer.clearRedirectsForTesting();
        }
    }
}
