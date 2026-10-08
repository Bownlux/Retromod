/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps pre-1.19.4 entity renderers drawing on a 1.19.4 or newer host.
 *
 * <p>1.19.4 moved a mob's limb swing from the {@code animationPosition}, {@code animationSpeed}
 * and {@code animationSpeedOld} fields into a {@code WalkAnimationState}, replaced the
 * {@code seeThrough} flag of {@code Font.drawInBatch} with a display mode, and dropped the float
 * {@code Mth.fastInvSqrt}. Model libraries such as GeckoLib 3 read the limb swing on every frame,
 * so the first rendered mob crashed the client. Reads go through the walk animation state, the
 * previous speed being its value at partial tick 0. A write to the speed sets it, and writes to
 * the position and previous speed are dropped, because the state only advances as a whole.
 */
public final class LegacyClientRenderBridge {

    static final String SYNTH = "com/retromod/generated/LegacyClientRender";

    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String WALK = "net/minecraft/world/entity/WalkAnimationState";
    private static final String FONT = "net/minecraft/client/gui/Font";
    private static final String DISPLAY_MODE = FONT + "$DisplayMode";
    private static final String BUFFERS = "Lnet/minecraft/client/renderer/MultiBufferSource;";
    private static final String[] TEXT_TYPES = {"Ljava/lang/String;", "Lnet/minecraft/network/chat/Component;",
        "Lnet/minecraft/util/FormattedCharSequence;"};
    private static final String[] MATRIX_TYPES = {"Lorg/joml/Matrix4f;", "Lcom/mojang/math/Matrix4f;"};

    private LegacyClientRenderBridge() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generate());
        String getter = "(L" + LIVING + ";)F";
        String setter = "(L" + LIVING + ";F)V";
        transformer.registerFieldStaticBridge(LIVING, "animationPosition", SYNTH, "walkPosition", getter,
                "ignoreWalk", setter);
        transformer.registerFieldStaticBridge(LIVING, "animationSpeed", SYNTH, "walkSpeed", getter,
                "setWalkSpeed", setter);
        transformer.registerFieldStaticBridge(LIVING, "animationSpeedOld", SYNTH, "walkSpeedOld", getter,
                "ignoreWalk", setter);

        transformer.registerMethodRedirect("net/minecraft/util/Mth", "fastInvSqrt", "(F)F",
                SYNTH, "fastInvSqrt", "(F)F");

        for (String text : TEXT_TYPES) {
            for (String matrix : MATRIX_TYPES) {
                String oldDesc = "(" + text + "FFIZ" + matrix + BUFFERS + "ZII)I";
                transformer.registerMethodRedirect(FONT, "drawInBatch", oldDesc, SYNTH, "drawInBatch",
                        "(L" + FONT + ";" + text + "FFIZLorg/joml/Matrix4f;" + BUFFERS + "ZII)I", true);
            }
        }
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);

        walkRead(cw, "walkPosition", "position", false);
        walkRead(cw, "walkSpeed", "speed", false);
        walkRead(cw, "walkSpeedOld", "speed", true);

        MethodVisitor setSpeed = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setWalkSpeed", "(L" + LIVING + ";F)V",
                null, null);
        setSpeed.visitCode();
        walkState(setSpeed);
        setSpeed.visitVarInsn(FLOAD, 1);
        setSpeed.visitMethodInsn(INVOKEVIRTUAL, WALK, "setSpeed", "(F)V", false);
        setSpeed.visitInsn(RETURN);
        setSpeed.visitMaxs(0, 0);
        setSpeed.visitEnd();

        MethodVisitor ignore = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "ignoreWalk", "(L" + LIVING + ";F)V",
                null, null);
        ignore.visitCode();
        ignore.visitInsn(RETURN);
        ignore.visitMaxs(0, 0);
        ignore.visitEnd();

        MethodVisitor invSqrt = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fastInvSqrt", "(F)F", null, null);
        invSqrt.visitCode();
        invSqrt.visitVarInsn(FLOAD, 0);
        invSqrt.visitInsn(F2D);
        invSqrt.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Mth", "fastInvSqrt", "(D)D", false);
        invSqrt.visitInsn(D2F);
        invSqrt.visitInsn(FRETURN);
        invSqrt.visitMaxs(0, 0);
        invSqrt.visitEnd();

        for (String text : TEXT_TYPES) {
            drawInBatch(cw, text);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void walkState(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, LIVING, "walkAnimation", "L" + WALK + ";");
    }

    private static void walkRead(ClassWriter cw, String name, String accessor, boolean atPreviousTick) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + LIVING + ";)F", null, null);
        mv.visitCode();
        walkState(mv);
        if (atPreviousTick) {
            mv.visitInsn(FCONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, WALK, accessor, "(F)F", false);
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, WALK, accessor, "()F", false);
        }
        mv.visitInsn(FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The old seeThrough flag picks the see-through display mode, otherwise the normal one. */
    private static void drawInBatch(ClassWriter cw, String text) {
        String desc = "(L" + FONT + ";" + text + "FFIZLorg/joml/Matrix4f;" + BUFFERS + "ZII)I";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "drawInBatch", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(FLOAD, 2);
        mv.visitVarInsn(FLOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 7);
        Label normal = new Label();
        Label chosen = new Label();
        mv.visitVarInsn(ILOAD, 8);
        mv.visitJumpInsn(IFEQ, normal);
        mv.visitFieldInsn(GETSTATIC, DISPLAY_MODE, "SEE_THROUGH", "L" + DISPLAY_MODE + ";");
        mv.visitJumpInsn(GOTO, chosen);
        mv.visitLabel(normal);
        mv.visitFieldInsn(GETSTATIC, DISPLAY_MODE, "NORMAL", "L" + DISPLAY_MODE + ";");
        mv.visitLabel(chosen);
        mv.visitVarInsn(ILOAD, 9);
        mv.visitVarInsn(ILOAD, 10);
        mv.visitMethodInsn(INVOKEVIRTUAL, FONT, "drawInBatch",
                "(" + text + "FFIZLorg/joml/Matrix4f;" + BUFFERS + "L" + DISPLAY_MODE + ";II)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
