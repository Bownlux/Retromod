/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #180 (AutoClicky): the mod does {@code mc.setScreen(cond ? new NewCombat() : new OldCombat())},
 * where {@code NewCombat extends OldCombat extends Screen}. When Retromod re-emits that lambda with
 * {@code COMPUTE_FRAMES}, ASM must merge {@code NewCombat} and {@code OldCombat} at the join before
 * {@code setScreen}. Neither mod class is on the transform classpath, so ASM's {@code Class.forName}
 * path failed and the writer collapsed the merge to {@code java/lang/Object} - a too-wide stack-map
 * frame the JVM then rejects at the {@code setScreen(Screen)} call ({@code VerifyError: Bad type on
 * operand stack}). With the jar's class bytes available, {@code getCommonSuperClass} resolves the
 * real common superclass ({@code OldCombat}) and the frame is valid.
 */
class MergeFrameModClassTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedVersion;

    @AfterEach
    void tearDown() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        transformer.clearJarClassBytesProvider();
        transformer.clearRedirectsForTesting();
    }

    private static byte[] modClass(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        cw.visitMethod(ACC_PUBLIC, "open", "()V", null, null).visitCode(); // (no body needed for merge test)
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A caller with `cond ? new NewCombat() : new OldCombat()` used as OldCombat, with valid input frames. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "rmtest/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(Z)V", null, null);
        mv.visitCode();
        Label elseB = new Label(), join = new Label();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitJumpInsn(IFEQ, elseB);
        mv.visitTypeInsn(NEW, "rmtest/NewCombat");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "rmtest/NewCombat", "<init>", "()V", false);
        mv.visitJumpInsn(GOTO, join);
        mv.visitLabel(elseB);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitTypeInsn(NEW, "rmtest/OldCombat");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "rmtest/OldCombat", "<init>", "()V", false);
        mv.visitLabel(join);
        // The correct original frame: the merged value is an OldCombat (NewCombat is-a OldCombat).
        mv.visitFrame(F_SAME1, 0, null, 1, new Object[]{"rmtest/OldCombat"});
        // open() is method-redirected below (open -> openRenamed), which forces a real COMPUTE_FRAMES
        // re-emit (a class with no matching redirect short-circuits, returning the original bytes).
        mv.visitMethodInsn(INVOKEVIRTUAL, "rmtest/OldCombat", "open", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String mergedFrameType(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("run")) continue;
            FrameNode last = null;
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof FrameNode fn) last = fn;
            }
            // the final frame is the one at the join, right before the invokevirtual
            if (last != null && last.stack != null && !last.stack.isEmpty()) {
                return String.valueOf(last.stack.get(last.stack.size() - 1));
            }
        }
        return null;
    }

    @Test
    @DisplayName("with the jar class-bytes provider, the merge resolves to the mod superclass (valid frame)")
    void mergeResolvesToModSuper() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        byte[] oldCombat = modClass("rmtest/OldCombat", "java/lang/Object");
        byte[] newCombat = modClass("rmtest/NewCombat", "rmtest/OldCombat");
        Function<String, byte[]> provider = n ->
                "rmtest/OldCombat".equals(n) ? oldCombat
                        : "rmtest/NewCombat".equals(n) ? newCombat : null;

        // A redirect that actually modifies the caller (open -> openRenamed) forces the COMPUTE_FRAMES
        // re-emit that recomputes the merge (an untouched class short-circuits to the original bytes).
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect(
                "rmtest/OldCombat", "open", "()V", "rmtest/OldCombat", "openRenamed", "()V");

        transformer.setJarClassBytesProvider(provider);
        byte[] withProvider = transformer.transformClass(caller(), "rmtest/Caller");
        assertEquals("rmtest/OldCombat", mergedFrameType(withProvider),
                "the merged stack type must be the real mod superclass, not Object");

        // Contrast: without the provider the merge collapses to Object (the #180 bug), which would be
        // a too-wide frame the JVM rejects at the OldCombat.open() call.
        transformer.clearJarClassBytesProvider();
        byte[] noProvider = transformer.transformClass(caller(), "rmtest/Caller");
        assertEquals("java/lang/Object", mergedFrameType(noProvider),
                "without the jar bytes the merge collapses to Object (the pre-fix behavior)");
    }
}
