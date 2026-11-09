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
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** YUNG's API sorts its registrations with instanceof StructurePlacementType, which 26.3 removed. */
class LegacyStructurePlacementBridgeTest {

    private static final String PLACEMENT = LegacyStructurePlacementBridge.PLACEMENT;
    private static final String TYPE = LegacyStructurePlacementBridge.TYPE;
    private static final String LEGACY_TYPE = LegacyStructurePlacementBridge.LEGACY_TYPE;
    private static final String BASE = LegacyStructurePlacementBridge.BASE;
    private static final String SPREADING = "net/minecraft/world/level/levelgen/structure/placement/AbstractSpreadingStructurePlacement";
    private static final String MAP_CODEC = "Lcom/mojang/serialization/MapCodec;";
    private static final String CTOR = "(Lnet/minecraft/core/Vec3i;L" + PLACEMENT + "$FrequencyReductionMethod;FIL"
            + "java/util/Optional;)V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion("1.21.1");
        // The 26.3 moves rename the nested types; registered here as they are in a real chain.
        transformer.registerClassRedirect(PLACEMENT + "$FrequencyReductionMethod", SPREADING + "$FrequencyReductionMethod");
        transformer.registerClassRedirect(PLACEMENT + "$ExclusionZone", SPREADING + "$ExclusionZone");
        LegacyStructurePlacementBridge.registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    void anOldPlacementMovesOntoTheRebuiltBase() {
        ClassNode node = transform(oldPlacement(), "mod/RingPlacement");

        assertEquals(BASE, node.superName, "26.3's placement is an interface; the base keeps the old fields");
        MethodInsnNode superCall = calls(node, "<init>").get(0);
        assertEquals(BASE + ".<init>(Lnet/minecraft/core/Vec3i;L" + SPREADING + "$FrequencyReductionMethod;FILjava/util/Optional;)V",
                superCall.owner + "." + superCall.name + superCall.desc);
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("type") && m.desc.equals("()L" + LEGACY_TYPE + ";")),
                "the type the base reads its codec from");
    }

    @Test
    void typesAreBuiltAsTheirOwnCodecClassAndToldApart() {
        ClassNode node = transform(registrations(), "mod/Registrations");

        TypeInsnNode check = null;
        for (AbstractInsnNode insn : method(node, "route").instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF) check = type;
        }
        assertEquals(LEGACY_TYPE, check.desc, "a placement type is still told apart from other codecs");

        List<String> made = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, "type").instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) made.add("indy " + indy.desc);
            if (insn instanceof MethodInsnNode call) made.add(call.owner + "." + call.name);
        }
        assertTrue(made.contains(LEGACY_TYPE + ".wrap"), "the () -> CODEC lambda is wrapped: " + made);
        assertFalse(made.contains("indy ()L" + TYPE + ";"), "no lambda of the removed interface is left: " + made);

        MethodInsnNode codec = calls(node, "codecOf").get(0);
        assertEquals(LegacyStructurePlacementBridge.TYPE_CODEC + ".codecOf(Ljava/lang/Object;)" + MAP_CODEC,
                codec.owner + "." + codec.name + codec.desc);
    }

    @Test
    void aModBuiltFor263KeepsItsOwnName() {
        transformer.setModSourceVersion("26.3");

        ClassNode node = transform(registrations(), "mod/Registrations");

        for (AbstractInsnNode insn : method(node, "route").instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF) {
                assertEquals(TYPE, type.desc, "only a mod built before 26.3 can name the removed type");
            }
        }
    }

    // ---- fixtures ----

    private ClassNode transform(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, name)).accept(node, 0);
        return node;
    }

    /** A 1.21.1 placement that extends the abstract StructurePlacement and names its type. */
    private static byte[] oldPlacement() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/RingPlacement", null, PLACEMENT, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitVarInsn(Opcodes.FLOAD, 3);
        init.visitVarInsn(Opcodes.ILOAD, 4);
        init.visitVarInsn(Opcodes.ALOAD, 5);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, PLACEMENT, "<init>", CTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor type = cw.visitMethod(Opcodes.ACC_PUBLIC, "type", "()L" + TYPE + ";", null, null);
        type.visitCode();
        type.visitInsn(Opcodes.ACONST_NULL);
        type.visitInsn(Opcodes.ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** YUNG's API's router check, a () -> CODEC type, and a codec() read of a type. */
    private static byte[] registrations() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/Registrations", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CODEC", MAP_CODEC, null, null).visitEnd();

        MethodVisitor route = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "route", "(Ljava/lang/Object;)Z", null, null);
        route.visitCode();
        route.visitVarInsn(Opcodes.ALOAD, 0);
        route.visitTypeInsn(Opcodes.INSTANCEOF, TYPE);
        route.visitInsn(Opcodes.IRETURN);
        route.visitMaxs(0, 0);
        route.visitEnd();

        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$type$0", "()" + MAP_CODEC, null, null);
        body.visitCode();
        body.visitFieldInsn(Opcodes.GETSTATIC, "mod/Registrations", "CODEC", MAP_CODEC);
        body.visitInsn(Opcodes.ARETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();

        MethodVisitor type = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "type", "()L" + TYPE + ";", null, null);
        type.visitCode();
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        type.visitInvokeDynamicInsn("codec", "()L" + TYPE + ";", metafactory, Type.getType("()" + MAP_CODEC),
                new Handle(Opcodes.H_INVOKESTATIC, "mod/Registrations", "lambda$type$0", "()" + MAP_CODEC, false),
                Type.getType("()" + MAP_CODEC));
        type.visitInsn(Opcodes.ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();

        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(L" + TYPE + ";)" + MAP_CODEC, null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEINTERFACE, TYPE, "codec", "()" + MAP_CODEC, true);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    /** Calls whose name is {@code name}, or every call in the constructor for {@code <init>}. */
    private static List<MethodInsnNode> calls(ClassNode node, String name) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (name.equals("<init>") ? method.name.equals("<init>") : call.name.equals(name)) calls.add(call);
            }
        }
        return calls;
    }
}
