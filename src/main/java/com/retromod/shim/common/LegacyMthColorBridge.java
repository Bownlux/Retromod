/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Answers {@code Mth.color(float, float, float)}, which 1.21.2 removed, with the same packed color. */
public final class LegacyMthColorBridge {

    static final String MTH = "net/minecraft/util/Mth";
    static final String HELPER = "com/retromod/generated/LegacyMthColor";
    static final String DESC = "(FFF)I";

    private LegacyMthColorBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (!hostLacksColor(ClassResourceInspector.read(MTH))) return;
        if (!transformer.getSyntheticClasses().containsKey(HELPER)) {
            transformer.registerSyntheticClass(HELPER, generate());
        }
        transformer.registerMethodRedirect(MTH, "color", DESC, HELPER, "color", DESC);
    }

    /** Whether the host lost the method; offline, whether the target is 1.21.2 or newer. */
    static boolean hostLacksColor(ClassNode hostMth) {
        if (hostMth == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0;
        }
        return hostMth.methods.stream().noneMatch(m -> m.name.equals("color") && m.desc.equals(DESC));
    }

    /**
     * {@code static int color(float r, float g, float b)}: each channel scaled to 255 and floored,
     * packed as red, green, blue with an alpha of zero, as 1.20.1 to 1.21.1 returned it.
     */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "color", DESC, null, null);
        mv.visitCode();
        for (int channel = 0; channel < 3; channel++) {
            mv.visitVarInsn(FLOAD, channel);
            mv.visitLdcInsn(255.0f);
            mv.visitInsn(FMUL);
            mv.visitInsn(F2D);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floor", "(D)D", false);
            mv.visitInsn(D2I);
            // red is shifted 16, green 8, blue stays; each is or'ed into the running value
            if (channel < 2) {
                mv.visitIntInsn(BIPUSH, channel == 0 ? 16 : 8);
                mv.visitInsn(ISHL);
            }
            if (channel > 0) mv.visitInsn(IOR);
        }
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
