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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges {@code DyeColor.getTextureDiffuseColors()}, which 1.21 replaced with
 * {@code getTextureDiffuseColor()}, for Mojang-named mods built for 1.20.6 or older.
 *
 * <p>The old method returned red, green and blue as floats from 0 to 1, and the new one returns
 * one ARGB int. Mods often read it while building dyed models or creature variants during setup,
 * so the stale call stops the whole mod with {@code NoSuchMethodError}. A generated helper splits
 * the int back into the three floats. Registered only when the host proves the change; offline,
 * where the host is not readable, the host version decides.
 */
public final class LegacyDyeColorBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyDyeColors";
    static final String DYE_COLOR = "net/minecraft/world/item/DyeColor";
    static final String OLD_NAME = "getTextureDiffuseColors";
    static final String OLD_DESC = "()[F";
    static final String NEW_NAME = "getTextureDiffuseColor";
    static final String HELPER_DESC = "(L" + DYE_COLOR + ";)[F";

    private LegacyDyeColorBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (!hostUsesArgbColor()) return;
        registerRedirects(transformer);
        LOGGER.info("Bridged DyeColor.getTextureDiffuseColors onto its 1.21 ARGB color");
    }

    static boolean hostUsesArgbColor() {
        ClassNode dye = ClassResourceInspector.read(DYE_COLOR);
        if (dye == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21") >= 0;
        boolean hasOld = dye.methods.stream().anyMatch(m -> m.name.equals(OLD_NAME) && m.desc.equals(OLD_DESC));
        boolean hasNew = dye.methods.stream().anyMatch(m -> m.name.equals(NEW_NAME) && m.desc.equals("()I"));
        return hasNew && !hasOld;
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerMethodRedirect(DYE_COLOR, OLD_NAME, OLD_DESC, HELPER, OLD_NAME, HELPER_DESC, true);
    }

    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, OLD_NAME, HELPER_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DYE_COLOR, NEW_NAME, "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        int[] shifts = {16, 8, 0};
        for (int i = 0; i < shifts.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, i);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitIntInsn(Opcodes.BIPUSH, shifts[i]);
            mv.visitInsn(Opcodes.ISHR);
            mv.visitIntInsn(Opcodes.SIPUSH, 255);
            mv.visitInsn(Opcodes.IAND);
            mv.visitInsn(Opcodes.I2F);
            mv.visitLdcInsn(255.0f);
            mv.visitInsn(Opcodes.FDIV);
            mv.visitInsn(Opcodes.FASTORE);
        }
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
