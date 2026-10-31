/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #377: Saint's Dragons' fireball overrides {@code Entity.getEyeHeight(Pose)}, which 1.20.5 made
 * final, so NeoForge 1.21.1 refused to load the class with {@code IncompatibleClassChangeError}.
 */
class FinalEyeHeightAdapterTest {

    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String POSE = "net/minecraft/world/entity/Pose";
    private static final String DIMS = "net/minecraft/world/entity/EntityDimensions";
    private static final String FIREBALL = "mod/Fireball";

    @Test
    @DisplayName("An old eye height override loads and still sets the eye height through the dimensions")
    void oldOverrideLoadsAndKeepsItsValue() throws Exception {
        byte[] repaired = FinalEyeHeightAdapter.apply(fireball(), "1.21.1", (child, ancestor) -> ancestor.equals(ENTITY));

        Class<?> fireball = loader(repaired).loadClass(FIREBALL.replace('/', '.'));
        Object entity = fireball.getConstructor().newInstance();
        Object pose = fireball.getClassLoader().loadClass(POSE.replace('/', '.')).getConstructor().newInstance();
        float eyeHeight = (float) fireball.getMethod("getEyeHeight", pose.getClass()).invoke(entity, pose);
        assertEquals(1.5f, eyeHeight, "the old override adds 0.5 to the base eye height of 1.0");
    }

    @Test
    @DisplayName("Hosts before 1.20.5, where the override is legal, are left alone")
    void olderHostsAreLeftAlone() {
        byte[] original = fireball();
        assertSame(original, FinalEyeHeightAdapter.apply(original, "1.20.4", (child, ancestor) -> true));
    }

    /** A loader that defines the stand-in host classes and the repaired mod class. */
    private static ClassLoader loader(byte[] modClass) {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(POSE, pose());
        classes.put(DIMS, dimensions());
        classes.put(ENTITY, entity());
        classes.put(FIREBALL, modClass);
        return new ClassLoader(FinalEyeHeightAdapterTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** {@code class Fireball extends Entity { float getEyeHeight(Pose p) { return super.getEyeHeight(p) + 0.5f; } }} */
    private static byte[] fireball() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FIREBALL, null, ENTITY, null);
        constructor(cw, ENTITY);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getEyeHeight", "(L" + POSE + ";)F", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, ENTITY, "getEyeHeight", "(L" + POSE + ";)F", false);
        mv.visitLdcInsn(0.5f);
        mv.visitInsn(FADD);
        mv.visitInsn(FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.20.5 shape: a final getEyeHeight that reads the eye height off getDimensions. */
    private static byte[] entity() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, ENTITY, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        MethodVisitor dims = cw.visitMethod(ACC_PUBLIC, "getDimensions", "(L" + POSE + ";)L" + DIMS + ";", null, null);
        dims.visitCode();
        dims.visitTypeInsn(NEW, DIMS);
        dims.visitInsn(DUP);
        dims.visitInsn(FCONST_1);
        dims.visitMethodInsn(INVOKESPECIAL, DIMS, "<init>", "(F)V", false);
        dims.visitInsn(ARETURN);
        dims.visitMaxs(0, 0);
        dims.visitEnd();
        MethodVisitor eye = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "getEyeHeight", "(L" + POSE + ";)F", null, null);
        eye.visitCode();
        eye.visitVarInsn(ALOAD, 0);
        eye.visitVarInsn(ALOAD, 1);
        eye.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "getDimensions", "(L" + POSE + ";)L" + DIMS + ";", false);
        eye.visitFieldInsn(GETFIELD, DIMS, "eyeHeight", "F");
        eye.visitInsn(FRETURN);
        eye.visitMaxs(0, 0);
        eye.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] dimensions() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, DIMS, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "eyeHeight", "F", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(F)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(FLOAD, 1);
        init.visitFieldInsn(PUTFIELD, DIMS, "eyeHeight", "F");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor with = cw.visitMethod(ACC_PUBLIC, "withEyeHeight", "(F)L" + DIMS + ";", null, null);
        with.visitCode();
        with.visitTypeInsn(NEW, DIMS);
        with.visitInsn(DUP);
        with.visitVarInsn(FLOAD, 1);
        with.visitMethodInsn(INVOKESPECIAL, DIMS, "<init>", "(F)V", false);
        with.visitInsn(ARETURN);
        with.visitMaxs(0, 0);
        with.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] pose() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, POSE, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
