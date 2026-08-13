/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.F_SAME1;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

/**
 * A Fabric class is framed after its intermediary superclass has been remapped. The class bytes
 * provider still returns the original declaration, so hierarchy resolution must remap each parent
 * read from those bytes before it walks the host hierarchy. Revamped Phantoms hit this when a
 * projectile and its Entity owner met at a branch join in Shockwave.onHit.
 */
class RemappedHierarchyFrameMergeTest {

    private static final String CHILD = "rmtest/RemappedChild";
    private static final String OLD_LIST = "old/ArrayList";
    private static final String OLD_ABSTRACT_LIST = "old/AbstractList";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedVersion;

    @AfterEach
    void tearDown() {
        transformer.clearJarClassBytesProvider();
        transformer.clearRedirectsForTesting();
        if (savedVersion != null) {
            RetromodVersion.TARGET_MC_VERSION = savedVersion;
        }
    }

    @Test
    void remapsOriginalJarParentsBeforeComputingFrames() throws Exception {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        byte[] original = oldChild();

        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_LIST, "java/util/ArrayList");
        transformer.registerClassRedirect(OLD_ABSTRACT_LIST, "java/util/AbstractList");
        transformer.setJarClassBytesProvider(name -> CHILD.equals(name) ? original : null);

        assertEquals("java/util/AbstractList", RetromodTransformer.commonSuperViaBytes(
                "java/util/AbstractList", CHILD,
                name -> CHILD.equals(name) ? original : null,
                name -> switch (name) {
                    case OLD_LIST -> "java/util/ArrayList";
                    case OLD_ABSTRACT_LIST -> "java/util/AbstractList";
                    default -> name;
                }));

        byte[] transformed = transformer.transformClass(original, CHILD);
        assertNotNull(transformed);

        Class<?> child = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass("rmtest.RemappedChild", bytes, 0, bytes.length);
            }
        }.define(transformed);
        Object instance = child.getConstructor().newInstance();
        Method chooseSize = child.getMethod("chooseSize", boolean.class, AbstractList.class);

        assertEquals(0, chooseSize.invoke(instance, true, new ArrayList<>()));
        assertEquals(0, chooseSize.invoke(instance, false, new ArrayList<>()));
    }

    private static byte[] oldChild() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, CHILD, null, OLD_LIST, null);

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, OLD_LIST, "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "chooseSize",
                "(ZLold/AbstractList;)I", null, null);
        mv.visitCode();
        Label useThis = new Label();
        Label join = new Label();
        mv.visitVarInsn(ILOAD, 1);
        mv.visitJumpInsn(IFEQ, useThis);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(GOTO, join);
        mv.visitLabel(useThis);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLabel(join);
        mv.visitFrame(F_SAME1, 0, null, 1, new Object[]{OLD_ABSTRACT_LIST});
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_ABSTRACT_LIST, "size", "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
