/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
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

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
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
}
