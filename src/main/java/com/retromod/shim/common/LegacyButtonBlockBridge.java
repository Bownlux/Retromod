/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps mods that build or extend {@code WoodButtonBlock} and {@code StoneButtonBlock} loading.
 *
 * <p>1.19.4 folded both into {@code ButtonBlock}, which now takes a {@code BlockSetType} for its
 * sounds, how long it stays pressed, and, before 1.20.5, whether arrows can press it. A mod built
 * for 1.19.2 calls the old one-argument {@code (Properties)} constructor, which MCreator generates
 * for every button. Each old class becomes a generated {@code ButtonBlock} subclass with that
 * constructor and the vanilla values: an oak button stays pressed for 30 ticks and reacts to
 * arrows, a stone button for 20 ticks and does not.
 */
public final class LegacyButtonBlockBridge {

    private static final String BLOCKS = "net/minecraft/world/level/block/";
    private static final String BUTTON = BLOCKS + "ButtonBlock";
    private static final String SET_TYPE = "net/minecraft/world/level/block/state/properties/BlockSetType";
    private static final String L_SET_TYPE = "L" + SET_TYPE + ";";
    private static final String L_PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";

    private LegacyButtonBlockBridge() {}

    public static void register(RetromodTransformer transformer) {
        boolean propertiesLast = RetromodVersion.compareMcVersions(
                RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
        registerButton(transformer, "WoodButtonBlock", "OAK", 30, true, propertiesLast);
        registerButton(transformer, "StoneButtonBlock", "STONE", 20, false, propertiesLast);
    }

    private static void registerButton(RetromodTransformer transformer, String removed,
            String setType, int ticksPressed, boolean arrowsCanPress, boolean propertiesLast) {
        String generated = "com/retromod/generated/Legacy" + removed;
        transformer.registerSyntheticClass(generated,
                generate(generated, setType, ticksPressed, arrowsCanPress, propertiesLast));
        transformer.registerClassRedirect(BLOCKS + removed, generated);
    }

    /** {@code class Legacy<Name> extends ButtonBlock} with the old {@code (Properties)} constructor. */
    static byte[] generate(String name, String setType, int ticksPressed, boolean arrowsCanPress,
            boolean propertiesLast) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, BUTTON, null);

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_PROPERTIES + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        if (propertiesLast) {
            ctor.visitFieldInsn(GETSTATIC, SET_TYPE, setType, L_SET_TYPE);
            ctor.visitIntInsn(BIPUSH, ticksPressed);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitMethodInsn(INVOKESPECIAL, BUTTON, "<init>",
                    "(" + L_SET_TYPE + "I" + L_PROPERTIES + ")V", false);
        } else {
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(GETSTATIC, SET_TYPE, setType, L_SET_TYPE);
            ctor.visitIntInsn(BIPUSH, ticksPressed);
            ctor.visitInsn(arrowsCanPress ? ICONST_1 : ICONST_0);
            ctor.visitMethodInsn(INVOKESPECIAL, BUTTON, "<init>",
                    "(" + L_PROPERTIES + L_SET_TYPE + "IZ)V", false);
        }
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
