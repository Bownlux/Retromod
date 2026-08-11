/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

/**
 * Bridges client state that moved from {@code Minecraft} to {@code Gui} in 26.2.
 *
 * <p>The old receiver cannot be redirected straight to {@code Gui}: the stack contains a
 * {@code Minecraft}, not its {@code gui} field. Static forwarders keep the old call shape while
 * inserting that field hop. The synthetic is embedded only into mods that reference it.
 */
public final class MinecraftToGuiHopSynthetic {

    private MinecraftToGuiHopSynthetic() {}

    static final String INTERNAL = "com/retromod/generated/MinecraftToGuiHop";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String GUI_DESC = "L" + GUI + ";";
    private static final String OVERLAY = "net/minecraft/client/gui/screens/Overlay";
    private static final String OVERLAY_DESC = "L" + OVERLAY + ";";

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);

        MethodVisitor getOverlay = cw.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getOverlay",
                "(L" + MINECRAFT + ";)" + OVERLAY_DESC,
                null,
                null);
        getOverlay.visitCode();
        getOverlay.visitVarInsn(ALOAD, 0);
        getOverlay.visitFieldInsn(GETFIELD, MINECRAFT, "gui", GUI_DESC);
        getOverlay.visitMethodInsn(INVOKEVIRTUAL, GUI, "overlay", "()" + OVERLAY_DESC, false);
        getOverlay.visitInsn(ARETURN);
        getOverlay.visitMaxs(0, 0);
        getOverlay.visitEnd();

        MethodVisitor setOverlay = cw.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "setOverlay",
                "(L" + MINECRAFT + ";" + OVERLAY_DESC + ")V",
                null,
                null);
        setOverlay.visitCode();
        setOverlay.visitVarInsn(ALOAD, 0);
        setOverlay.visitFieldInsn(GETFIELD, MINECRAFT, "gui", GUI_DESC);
        setOverlay.visitVarInsn(ALOAD, 1);
        setOverlay.visitMethodInsn(
                INVOKEVIRTUAL, GUI, "setOverlay", "(" + OVERLAY_DESC + ")V", false);
        setOverlay.visitInsn(RETURN);
        setOverlay.visitMaxs(0, 0);
        setOverlay.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    static void register(RetromodTransformer transformer) {
        if (!transformer.getSyntheticClasses().containsKey(INTERNAL)) {
            transformer.registerSyntheticClass(INTERNAL, generate());
        }

        transformer.registerMethodRedirect(
                MINECRAFT, "getOverlay", "()" + OVERLAY_DESC,
                INTERNAL, "getOverlay", "(L" + MINECRAFT + ";)" + OVERLAY_DESC,
                true);
        transformer.registerMethodRedirect(
                MINECRAFT, "setOverlay", "(" + OVERLAY_DESC + ")V",
                INTERNAL, "setOverlay", "(L" + MINECRAFT + ";" + OVERLAY_DESC + ")V",
                true);
    }
}
