/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keeps precise source frames when pre-26.1 Minecraft hierarchy bytes are unavailable (#179). */
class Pre26FramePreservationTest {

    private static final String ENTITY = "net/minecraft/class_1297";
    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void entityAndPlayerBranchDoesNotWidenToObject() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerFieldRedirect("probe/Flags", "field", "I",
                "probe/Flags", "renamed", "I");

        byte[] transformed = transformer.transformClass(fixture(), "probe/CameraRestore");
        String[] mergedType = {null};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!"restore".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local,
                            int numStack, Object[] stack) {
                        if (numStack == 1 && stack[0] instanceof String s) mergedType[0] = s;
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        assertEquals(ENTITY, mergedType[0]);
    }

    private static byte[] fixture() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/CameraRestore", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "restore",
                "(ZL" + ENTITY + ";Lnet/minecraft/class_746;)V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "probe/Flags", "field", "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        Label player = new Label();
        Label merged = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, player);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.GOTO, merged);
        mv.visitLabel(player);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitLabel(merged);
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{ENTITY});
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 4);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
