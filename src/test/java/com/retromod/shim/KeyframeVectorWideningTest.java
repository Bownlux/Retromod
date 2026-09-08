/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keyframe's constructors took the joml concrete-to-interface widening, the same one that moved
 * {@code Vec3.<init>(Vector3f)} to {@code Vector3fc}.
 *
 * <p>{@code Vector3f} implements {@code Vector3fc}, so at the source level nothing changed and mod
 * authors saw no reason to touch their code. Bytecode descriptors are exact, though, so every call
 * kept the concrete type and failed with {@code NoSuchMethodError}. Entity animation mods build
 * every keyframe through these constructors, so the reach is wide out of proportion to the size of
 * the change: two corpus mods alone made 4,760 such calls, and linking them against the real 26.2
 * and 26.3 jars showed every one unresolved before this.
 */
class KeyframeVectorWideningTest {

    private static final String KEYFRAME = "net/minecraft/client/animation/Keyframe";
    private static final String INTERP =
            "Lnet/minecraft/client/animation/AnimationChannel$Interpolation;";
    private static final String MOD = "test/anim/Animations";

    private RetromodTransformer transformer;
    private String savedTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    /** A mod building a keyframe the way the old signature allowed. */
    private static byte[] buildsKeyframe(String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, KEYFRAME);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.FCONST_0);
        for (int i = 0; i < desc.split("Lorg/joml/Vector3f;").length - 1; i++) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, KEYFRAME, "<init>", desc, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private MethodInsnNode keyframeCall(byte[] input) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(input, MOD)).accept(cn, 0);
        MethodNode make = cn.methods.stream()
                .filter(m -> m.name.equals("make")).findFirst().orElseThrow();
        for (AbstractInsnNode insn : make.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(KEYFRAME)) return call;
        }
        return fail("the keyframe construction disappeared");
    }

    @Test
    @DisplayName("the three argument constructor is widened to the joml interface")
    void widensThreeArgumentForm() {
        MethodInsnNode call = keyframeCall(
                buildsKeyframe("(FLorg/joml/Vector3f;" + INTERP + ")V"));
        assertEquals("(FLorg/joml/Vector3fc;" + INTERP + ")V", call.desc,
                "Vector3f implements Vector3fc, so only the descriptor moves and the value already "
                + "on the stack stays assignable");
    }

    @Test
    @DisplayName("the pre and post target form is widened too")
    void widensFourArgumentForm() {
        MethodInsnNode call = keyframeCall(
                buildsKeyframe("(FLorg/joml/Vector3f;Lorg/joml/Vector3f;" + INTERP + ")V"));
        assertEquals("(FLorg/joml/Vector3fc;Lorg/joml/Vector3fc;" + INTERP + ")V", call.desc);
    }

    @Test
    @DisplayName("the owner and the call form are untouched")
    void onlyTheDescriptorMoves() {
        MethodInsnNode call = keyframeCall(
                buildsKeyframe("(FLorg/joml/Vector3f;" + INTERP + ")V"));
        assertEquals(KEYFRAME, call.owner, "the class did not move, only its parameter types");
        assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode(),
                "a constructor stays an INVOKESPECIAL; rewriting it to a factory would need a real "
                + "factory to exist");
    }
}
