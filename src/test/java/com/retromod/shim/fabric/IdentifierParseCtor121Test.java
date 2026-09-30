/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #262 (KubeJS for 1.20.1 on Fabric 1.21.1): {@code new Identifier(String)} survived the transform
 * and threw {@code NoSuchMethodError} in {@code UtilsJS.<clinit>}. 1.21 made the constructor private,
 * and the host has three static {@code (String)} factories, so discovery declined to choose one.
 */
class IdentifierParseCtor121Test {

    private static final String IDENTIFIER = "net/minecraft/class_2960";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new Identifier(String) becomes Identifier.parse on a 1.21 host")
    void oneArgConstructorUsesParse() {
        new Fabric_1_20_6_to_1_21().registerRedirects(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Ids", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "id", "()Ljava/lang/Object;",
                null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, IDENTIFIER);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("kubejs:example");
        mv.visitMethodInsn(INVOKESPECIAL, IDENTIFIER, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Ids")).accept(out, 0);
        MethodInsnNode call = null;
        for (MethodNode m : out.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c && c.owner.equals(IDENTIFIER)) call = c;
                assertNotEquals(NEW, insn.getOpcode(), "the NEW/DUP pair must be removed");
            }
        }
        assertNotNull(call, "the Identifier call disappeared");
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals("method_60654", call.name, "method_60654 is parse, which throws like the ctor");
        assertEquals("(Ljava/lang/String;)L" + IDENTIFIER + ";", call.desc);
    }
}
