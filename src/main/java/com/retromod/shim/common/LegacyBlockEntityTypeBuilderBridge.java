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

import static org.objectweb.asm.Opcodes.*;

/** Keeps {@code BlockEntityType.Builder}, which 26.1 removed, building block entity types for Forge and NeoForge mods. */
public final class LegacyBlockEntityTypeBuilderBridge {

    static final String BUILDER = "com/retromod/generated/LegacyVanillaBlockEntityTypeBuilder";
    static final String OLD_BUILDER = "net/minecraft/world/level/block/entity/BlockEntityType$Builder";
    private static final String TYPE = "net/minecraft/world/level/block/entity/BlockEntityType";
    private static final String SUPPLIER = "L" + TYPE + "$BlockEntitySupplier;";
    private static final String BLOCKS = "[Lnet/minecraft/world/level/block/Block;";

    private LegacyBlockEntityTypeBuilderBridge() {}

    /** Registers on a host without the builder. Fabric mods go through Fabric API's builder instead. */
    public static void register(RetromodTransformer t) {
        boolean removed = ClassResourceInspector.read(TYPE) != null
                ? !ClassResourceInspector.exists(OLD_BUILDER)
                : RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        if (!removed) return;
        t.registerSyntheticClass(BUILDER, generate());
        t.registerClassRedirect(OLD_BUILDER, BUILDER);
    }

    /** {@code of(supplier, blocks)} keeps both; {@code build(type)} builds the type, which needs no data fixer type now. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, BUILDER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "factory", SUPPLIER, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "blocks", BLOCKS, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(" + SUPPLIER + BLOCKS + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, BUILDER, "factory", SUPPLIER);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, BUILDER, "blocks", BLOCKS);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_VARARGS, "of", "(" + SUPPLIER + BLOCKS + ")L" + BUILDER + ";", null, null);
        of.visitCode();
        of.visitTypeInsn(NEW, BUILDER);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitVarInsn(ALOAD, 1);
        of.visitMethodInsn(INVOKESPECIAL, BUILDER, "<init>", "(" + SUPPLIER + BLOCKS + ")V", false);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();

        MethodVisitor build = cw.visitMethod(ACC_PUBLIC, "build", "(Lcom/mojang/datafixers/types/Type;)L" + TYPE + ";", null, null);
        build.visitCode();
        build.visitTypeInsn(NEW, TYPE);
        build.visitInsn(DUP);
        build.visitVarInsn(ALOAD, 0);
        build.visitFieldInsn(GETFIELD, BUILDER, "factory", SUPPLIER);
        build.visitVarInsn(ALOAD, 0);
        build.visitFieldInsn(GETFIELD, BUILDER, "blocks", BLOCKS);
        build.visitMethodInsn(INVOKESPECIAL, TYPE, "<init>", "(" + SUPPLIER + BLOCKS + ")V", false);
        build.visitInsn(ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
