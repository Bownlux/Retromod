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
 * Keeps pre-1.20.3 sand and gravel blocks working.
 *
 * <p>1.20.3 deleted {@code SandBlock} and {@code GravelBlock} and folded both into
 * {@code ColoredFallingBlock}, whose constructor takes the dust color as a {@code ColorRGBA}
 * record instead of an {@code int}. Renaming the class alone leaves {@code new SandBlock(int,
 * Properties)} and a subclass's {@code super(int, Properties)} pointing at a constructor that does
 * not exist.
 *
 * <p>Both classes move to a generated {@code ColoredFallingBlock} subclass that keeps the old
 * constructors: the sand one wraps its color, and the gravel one supplies gravel's old dust color.
 * It is registered by the Forge 1.20 to NeoForge 1.21 shim, so it applies to Forge-family mods on
 * NeoForge and Forge 1.21 and newer, where class names are Mojang's. A type test or cast against
 * the old classes now names the generated class, so vanilla sand and gravel no longer match it.
 */
public final class LegacyFallingBlockBridge {

    public static final String GENERATED = "com/retromod/generated/LegacyColoredFallingBlock";
    static final String SAND = "net/minecraft/world/level/block/SandBlock";
    static final String GRAVEL = "net/minecraft/world/level/block/GravelBlock";
    private static final String COLORED = "net/minecraft/world/level/block/ColoredFallingBlock";
    private static final String COLOR = "net/minecraft/util/ColorRGBA";
    private static final String L_PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String HOST_CTOR = "(L" + COLOR + ";" + L_PROPERTIES + ")V";

    /** {@code GravelBlock.getDustColor} in 1.20.2, the color vanilla gravel still uses. */
    static final int GRAVEL_DUST_COLOR = -8356741;

    private LegacyFallingBlockBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.3") < 0) return;
        transformer.registerSyntheticClass(GENERATED, generate());
        transformer.registerClassRedirect(SAND, GENERATED);
        transformer.registerClassRedirect(GRAVEL, GENERATED);
    }

    /** {@code class LegacyColoredFallingBlock extends ColoredFallingBlock} with the old constructors. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, GENERATED, null, COLORED, null);

        MethodVisitor sand = cw.visitMethod(ACC_PUBLIC, "<init>", "(I" + L_PROPERTIES + ")V", null, null);
        sand.visitCode();
        sand.visitVarInsn(ALOAD, 0);
        newColor(sand, () -> sand.visitVarInsn(ILOAD, 1));
        sand.visitVarInsn(ALOAD, 2);
        sand.visitMethodInsn(INVOKESPECIAL, COLORED, "<init>", HOST_CTOR, false);
        sand.visitInsn(RETURN);
        sand.visitMaxs(0, 0);
        sand.visitEnd();

        MethodVisitor gravel = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_PROPERTIES + ")V", null, null);
        gravel.visitCode();
        gravel.visitVarInsn(ALOAD, 0);
        newColor(gravel, () -> gravel.visitLdcInsn(GRAVEL_DUST_COLOR));
        gravel.visitVarInsn(ALOAD, 1);
        gravel.visitMethodInsn(INVOKESPECIAL, COLORED, "<init>", HOST_CTOR, false);
        gravel.visitInsn(RETURN);
        gravel.visitMaxs(0, 0);
        gravel.visitEnd();

        // A mod that already moved to the record passes straight through.
        MethodVisitor host = cw.visitMethod(ACC_PUBLIC, "<init>", HOST_CTOR, null, null);
        host.visitCode();
        for (int slot = 0; slot <= 2; slot++) host.visitVarInsn(ALOAD, slot);
        host.visitMethodInsn(INVOKESPECIAL, COLORED, "<init>", HOST_CTOR, false);
        host.visitInsn(RETURN);
        host.visitMaxs(0, 0);
        host.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void newColor(MethodVisitor mv, Runnable pushColor) {
        mv.visitTypeInsn(NEW, COLOR);
        mv.visitInsn(DUP);
        pushColor.run();
        mv.visitMethodInsn(INVOKESPECIAL, COLOR, "<init>", "(I)V", false);
    }
}
