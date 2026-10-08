/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge {@code Lazy<Item> item = () -> ...} lambda against NeoForge's final {@code Lazy} class. */
class LegacyLazyLambdaAdapterTest {

    private static final String LAZY = LegacyLazyLambdaAdapter.LAZY;

    @Test
    void aLazyLambdaIsBuiltAsASupplierAndWrapped() {
        ClassNode node = node(LegacyLazyLambdaAdapter.rewrite(lambdaReturning(LAZY)));

        AbstractInsnNode[] code = method(node, "make").instructions.toArray();
        InvokeDynamicInsnNode indy = null;
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof InvokeDynamicInsnNode found) {
                indy = found;
                MethodInsnNode wrap = (MethodInsnNode) code[i + 1];
                assertEquals(LAZY, wrap.owner);
                assertEquals("of", wrap.name);
                assertFalse(wrap.itf, "NeoForge's Lazy is a class");
            }
        }
        assertNotNull(indy);
        assertEquals("()Ljava/util/function/Supplier;", indy.desc,
                "a class cannot be a lambda target, so the lambda must implement Supplier");
    }

    @Test
    void aLambdaOfAnotherTypeIsLeftAlone() {
        byte[] supplier = lambdaReturning("java/util/function/Supplier");

        assertSame(supplier, LegacyLazyLambdaAdapter.rewrite(supplier));
    }

    /** {@code static T make() { return () -> "value"; }} with the lambda typed as {@code owner}. */
    private static byte[] lambdaReturning(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Items", null, "java/lang/Object", null);
        MethodVisitor make = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()L" + owner + ";", null, null);
        make.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        make.visitInvokeDynamicInsn("get", "()L" + owner + ";", metafactory,
                Type.getType("()Ljava/lang/Object;"),
                new Handle(H_INVOKESTATIC, "test/mod/Items", "lambda$make$0", "()Ljava/lang/Object;", false),
                Type.getType("()Ljava/lang/Object;"));
        make.visitInsn(ARETURN);
        make.visitMaxs(0, 0);
        make.visitEnd();
        MethodVisitor body = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$make$0",
                "()Ljava/lang/Object;", null, null);
        body.visitCode();
        body.visitLdcInsn("value");
        body.visitInsn(ARETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
