/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Generates {@code com/retromod/generated/RetroQuat2D}: the quaternion-to-2D-angle helper the GUI
 * 2D-transform migration's Phase 3 uses to turn {@code pose().mulPose(Quaternionf[c])} into the 2D
 * {@code Matrix3x2fStack.rotate(zAngle)}.
 *
 * <p>{@code static float zAngle(Quaternionfc q) { return (float) (2 * Math.atan2(q.z(), q.w())); }}
 * For a pure-Z rotation quaternion ({@code z = sin(theta/2), w = cos(theta/2)}) this recovers theta
 * exactly, and pure-Z is the only rotation that ever made visual sense in 2D GUI space (clock hands,
 * compass needles, spinning icons); a non-Z quaternion was already garbage on screen pre-migration.
 *
 * <p>Generated (not compiled) because it references joml's {@code Quaternionfc} interface, which
 * Retromod doesn't compile against; registered as a synthetic so the per-mod embedder relocates a
 * copy into referencing mods on Forge/NeoForge (reference-gated, so mods without a migrated
 * {@code mulPose} never carry it; Fabric injects it directly).
 */
public final class Quat2DSynthetic {

    private Quat2DSynthetic() {}

    public static final String INTERNAL = "com/retromod/generated/RetroQuat2D";
    private static final String QUATC = "org/joml/Quaternionfc";

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "zAngle",
                "(L" + QUATC + ";)F", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, QUATC, "z", "()F", true);
        mv.visitInsn(F2D);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, QUATC, "w", "()F", true);
        mv.visitInsn(F2D);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "atan2", "(DD)D", false);
        mv.visitLdcInsn(2.0d);
        mv.visitInsn(DMUL);
        mv.visitInsn(D2F);
        mv.visitInsn(FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Register the synthetic (idempotent); harmless when unused (embedding is reference-gated). */
    public static void register(RetromodTransformer t) {
        if (!t.getSyntheticClasses().containsKey(INTERNAL)) {
            t.registerSyntheticClass(INTERNAL, generate());
        }
    }
}
