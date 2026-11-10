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
import org.objectweb.asm.tree.MethodNode;

/** Rebuilds the {@code BlockPos(Vec3i)} constructor that 26.3 removed. */
public final class LegacyBlockPosBridge {

    static final String HELPER = "com/retromod/generated/LegacyBlockPos";
    static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    static final String VEC3I = "net/minecraft/core/Vec3i";
    static final String OLD_CTOR = "(L" + VEC3I + ";)V";
    static final String XYZ_CTOR = "(III)V";
    static final String FACTORY_DESC = "(L" + VEC3I + ";)L" + BLOCK_POS + ";";

    private LegacyBlockPosBridge() {}

    /** Registers the bridge on a host whose {@code BlockPos} lost the constructor. */
    public static void register(RetromodTransformer transformer) {
        if (lostVec3iConstructor()) registerRedirects(transformer);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        // new BlockPos(vec) copies the three coordinates, which the int constructor still takes.
        transformer.registerConstructorRedirect(BLOCK_POS, OLD_CTOR, HELPER, "of", FACTORY_DESC);
        // A mod's own BlockPos subclass calls super(vec); unpack the vector on the stack instead.
        transformer.registerSuperConstructorAdapter(BLOCK_POS, OLD_CTOR, XYZ_CTOR,
                LegacyBlockPosBridge::unpackVector);
    }

    /** Whether the host's {@code BlockPos} no longer takes a {@code Vec3i}. */
    static boolean lostVec3iConstructor() {
        ClassNode pos = ClassResourceInspector.read(BLOCK_POS);
        // Offline the host classes are not readable, so the host version decides.
        if (pos == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        return lostVec3iConstructor(pos);
    }

    static boolean lostVec3iConstructor(ClassNode pos) {
        boolean oldForm = false;
        boolean xyz = false;
        for (MethodNode method : pos.methods) {
            if (!method.name.equals("<init>")) continue;
            oldForm |= method.desc.equals(OLD_CTOR);
            xyz |= method.desc.equals(XYZ_CTOR);
        }
        return !oldForm && xyz;
    }

    /** Turns {@code [vec]} on top of the stack into {@code [x, y, z]}. */
    static void unpackVector(MethodVisitor mv) {
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3I, "getX", "()I", false);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3I, "getY", "()I", false);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3I, "getZ", "()I", false);
    }

    /** {@code of(vec)}: {@code new BlockPos(vec.getX(), vec.getY(), vec.getZ())}, as the constructor did. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        MethodVisitor of = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of", FACTORY_DESC, null, null);
        of.visitCode();
        of.visitTypeInsn(Opcodes.NEW, BLOCK_POS);
        of.visitInsn(Opcodes.DUP);
        of.visitVarInsn(Opcodes.ALOAD, 0);
        unpackVector(of);
        of.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_POS, "<init>", XYZ_CTOR, false);
        of.visitInsn(Opcodes.ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
