/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Moonlight's injection before {@code createRegistryAccess()} in {@code WorldLoader.load}, which 26.1 moved into {@code lambda$load$0}. */
class MixinLambdaRetargetTest {

    private static final String LOADER = "net/minecraft/server/WorldLoader";
    private static final String CALL = "net/minecraft/server/RegistryLayer";
    private static final String CONFIG = "Lnet/minecraft/server/WorldLoader$InitConfig;";
    private static final String EXECUTOR = "Ljava/util/concurrent/Executor;";
    private static final String PAIR = "Lcom/mojang/datafixers/util/Pair;";
    private static final String MANAGER = "Lnet/minecraft/server/packs/resources/CloseableResourceManager;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";

    @Test
    @DisplayName("on 26.x the handler moves into the lambda, keeping its callback and its @Local manager")
    void handlerMovesIntoTheLambda() {
        ClassNode mixin = mixin(3);
        assertTrue(MixinLambdaRetarget.apply(mixin, Map.of(LOADER, host(true))::get));

        MethodNode handler = mixin.methods.get(0);
        assertEquals("(" + EXECUTOR + CONFIG + PAIR + CIR + MANAGER + ")V", handler.desc);
        assertEquals(List.of("lambda$load$0"), value(handler.visibleAnnotations.get(0), "method"));
        assertEquals(4, loadedSlot(handler), "the manager moved past the lambda's extra argument");
        assertNotNull(handler.invisibleParameterAnnotations[4], "the @Local stays on the manager");
        assertEquals(LOCAL, handler.invisibleParameterAnnotations[4].get(0).desc);
        assertNull(handler.signature, "MixinExtras reads sugar types from the signature, which listed the old arguments");
    }

    @Test
    @DisplayName("a host whose load still makes the call, or a handler that reads load's arguments, is left alone")
    void leftAlone() {
        assertFalse(MixinLambdaRetarget.apply(mixin(3), Map.of(LOADER, host(false))::get), "1.21.11 calls it in load");
        assertFalse(MixinLambdaRetarget.apply(mixin(0), Map.of(LOADER, host(true))::get), "the handler reads initConfig");
        assertFalse(MixinLambdaRetarget.apply(mixin(3), name -> null), "an unreadable host");
        for (String extra : List.of("cancellable", "slice", "ordinal")) {
            assertFalse(MixinLambdaRetarget.apply(mixin(3, extra), Map.of(LOADER, host(true))::get),
                    extra + " means something else inside the lambda");
        }
    }

    /** {@code load(InitConfig, Executor)} and its {@code lambda$load$0(Executor, InitConfig, Pair)}; the call sits in one of them. */
    private static ClassNode host(boolean inLambda) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, LOADER, null, "java/lang/Object", null);
        body(cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load", "(" + CONFIG + EXECUTOR + ")Ljava/lang/Object;", null, null), !inLambda);
        body(cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$load$0",
                "(" + EXECUTOR + CONFIG + PAIR + ")Ljava/lang/Object;", null, null), inLambda);
        cw.visitEnd();
        return read(cw.toByteArray());
    }

    private static void body(MethodVisitor mv, boolean calls) {
        mv.visitCode();
        if (calls) {
            mv.visitMethodInsn(INVOKESTATIC, CALL, "createRegistryAccess", "()Ljava/lang/Object;", false);
            mv.visitInsn(POP);
        }
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The handler {@code (InitConfig, Executor, CallbackInfoReturnable, @Local CloseableResourceManager)}, loading slot {@code read}. */
    private static ClassNode mixin(int read) {
        return mixin(read, null);
    }

    /** The same, with one more {@code @Inject} value ({@code cancellable}, {@code slice}) or an {@code @At} ordinal. */
    private static ClassNode mixin(int read, String extra) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "mod/WorldLoaderMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(LOADER));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "moonlight$early",
                "(" + CONFIG + EXECUTOR + CIR + MANAGER + ")V",
                "(" + CONFIG + EXECUTOR + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<Ljava/lang/Object;>;"
                        + MANAGER + ")V", null);
        AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "load");
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", "L" + CALL + ";createRegistryAccess()Ljava/lang/Object;");
        if ("ordinal".equals(extra)) at.visit("ordinal", 1);
        at.visitEnd();
        ats.visitEnd();
        if ("cancellable".equals(extra)) inject.visit("cancellable", true);
        if ("slice".equals(extra)) {
            AnnotationVisitor slices = inject.visitArray("slice");
            slices.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/Slice;").visitEnd();
            slices.visitEnd();
        }
        inject.visitEnd();
        mv.visitParameterAnnotation(3, LOCAL, false).visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, read);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return read(cw.toByteArray());
    }

    private static int loadedSlot(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var) return var.var;
        }
        throw new AssertionError("no load");
    }

    private static Object value(AnnotationNode node, String key) {
        for (int i = 0; i < node.values.size(); i += 2) {
            if (node.values.get(i).equals(key)) return node.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
