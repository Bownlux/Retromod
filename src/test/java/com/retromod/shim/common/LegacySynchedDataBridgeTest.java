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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.List;

import static com.retromod.shim.common.LegacySynchedDataBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mob's {@code defineSynchedData()} override against the 1.20.5 builder. */
class LegacySynchedDataBridgeTest {

    private static final String MOB = "net/minecraft/world/entity/PathfinderMob";
    private static final String L_BUILDER = "L" + BUILDER + ";";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void anOldOverrideRunsFromTheBuilderOverload() throws Exception {
        ClassNode mob = transformLegacyMob();

        MethodNode legacy = method(mob, LEGACY_BODY, "()V");
        assertNotEquals(0, legacy.access & Opcodes.ACC_PRIVATE,
                "the old body must be private so a subclass body never dispatches to it twice");
        MethodNode bridge = method(mob, DEFINE, "(" + L_BUILDER + ")V");
        assertTrue(calls(bridge).contains(mob.name + "." + LEGACY_BODY),
                "the builder overload must run the old body: " + calls(bridge));
        for (MethodNode method : mob.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(mob.name, method);
        }
    }

    @Test
    void theOldBodyChainsToTheParentBuilderOverloadAndDefinesOnTheBuilder() {
        ClassNode mob = transformLegacyMob();

        List<String> body = calls(method(mob, LEGACY_BODY, "()V"));

        assertTrue(body.contains(HELPER + ".current"), "the parent call needs the builder: " + body);
        assertTrue(body.contains(MOB + "." + DEFINE + "(" + L_BUILDER + ")V"),
                "super.defineSynchedData() must reach the parent's builder overload: " + body);
        assertTrue(body.contains(HELPER + ".define"), "entityData.define must reach the builder: " + body);
    }

    @Test
    void aClassWithoutTheOldOverrideIsUnchanged() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);
        byte[] plain = mob(false);

        byte[] result = apply(plain);

        assertSame(plain, result, "a class with nothing to bridge must be returned as is");
    }

    private static ClassNode transformLegacyMob() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);
        byte[] transformed = transformer.transformClass(mob(true), "test/LegacyMob.class");
        ClassNode node = new ClassNode();
        new ClassReader(apply(transformed)).accept(node, 0);
        return node;
    }

    /** {@code protected void defineSynchedData() { super.defineSynchedData(); entityData.define(A, 0); }} */
    private static byte[] mob(boolean legacyOverride) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyMob", null, MOB, null);
        if (legacyOverride) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, DEFINE, "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MOB, DEFINE, "()V", false);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, "test/LegacyMob", "entityData", "L" + DATA + ";");
            mv.visitFieldInsn(Opcodes.GETSTATIC, "test/LegacyMob", "VARIANT", "L" + ACCESSOR + ";");
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA, "define",
                    "(L" + ACCESSOR + ";Ljava/lang/Object;)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner + "." + call.name
                        + (call.name.equals(DEFINE) ? call.desc : ""));
            }
        }
        return calls;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + name + desc));
    }
}
