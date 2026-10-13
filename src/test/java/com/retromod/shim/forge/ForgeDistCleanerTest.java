/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Mythic Fantasies, a 1.19.2 Forge mod, on a NeoForge 26.3 server: its dimension's client-setup
 * handler, marked {@code @OnlyIn(Dist.CLIENT)}, used a private lambda typed with a client class,
 * and NeoForge failed the mod's subscriber registration listing it.
 */
class ForgeDistCleanerTest {

    private static final String OWNER = "mod/Fixers";
    private static final String CLIENT_TYPE = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;";

    @Test
    @DisplayName("a client-only handler and the lambda only it used are removed; common members stay")
    void removesClientOnlyHandlerAndItsLambda() {
        ClassNode node = read(ForgeDistCleaner.stripClientOnly(fixers()));
        assertEquals(List.of("<init>", "commonSetup"), node.methods.stream().map(m -> m.name).toList());
        assertEquals(List.of("commonField"), node.fields.stream().map(f -> f.name).toList());
    }

    @Test
    @DisplayName("a server-only member and a class without the marker are left alone")
    void leavesOtherMembers() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        onlyIn(cw.visitField(ACC_PUBLIC | ACC_STATIC, "serverField", "I", null, null)
                .visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIn;", true), "DEDICATED_SERVER");
        cw.visitEnd();
        byte[] in = cw.toByteArray();
        assertSame(in, ForgeDistCleaner.stripClientOnly(in));
    }

    private static byte[] fixers() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "commonField", "I", null, null).visitEnd();
        var clientField = cw.visitField(ACC_PUBLIC | ACC_STATIC, "sky", CLIENT_TYPE, null, null);
        onlyIn(clientField.visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIn;", true), "CLIENT");
        clientField.visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        empty(cw, ACC_PUBLIC | ACC_STATIC, "commonSetup", "(Ljava/lang/Object;)V");

        String lambdaDesc = "(" + CLIENT_TYPE + ")" + CLIENT_TYPE;
        MethodVisitor handler = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerDimensionSpecialEffects",
                "(Ljava/lang/Object;)V", null, null);
        onlyIn(handler.visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIn;", true), "CLIENT");
        handler.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        handler.visitInvokeDynamicInsn("apply", "()Ljava/util/function/UnaryOperator;", metafactory,
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(H_INVOKESTATIC, OWNER, "lambda$registerDimensionSpecialEffects$0", lambdaDesc, false),
                Type.getType(lambdaDesc));
        handler.visitInsn(POP);
        handler.visitInsn(RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();

        MethodVisitor lambda = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                "lambda$registerDimensionSpecialEffects$0", lambdaDesc, null, null);
        lambda.visitCode();
        lambda.visitVarInsn(ALOAD, 0);
        lambda.visitInsn(ARETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void onlyIn(AnnotationVisitor annotation, String dist) {
        annotation.visitEnum("value", "Lnet/minecraftforge/api/distmarker/Dist;", dist);
        annotation.visitEnd();
    }

    private static void empty(ClassWriter cw, int access, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
