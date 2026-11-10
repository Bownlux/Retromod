/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Two interfaces that a class move collapses into one name are written once, so the class loads. */
class InterfaceCollapseTest {

    private static final String ADDED = "test/collapse/Added";
    private static final String REMAP = "test/collapse/Remap";
    private static final String REMOVED = "test/collapse/Removed";
    private static final String OLD_BASE = "test/collapse/OldBase";
    private static final String NEW_BASE = "test/collapse/NewBase";
    private static final String TRACKER = "test/collapse/Tracker";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    // 1.21.1 keeps the original frames and 26.3 recomputes them, so both header passes run.
    @ParameterizedTest(name = "host {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("an interface moved onto one the class already lists is written once")
    void collapsedInterfacesAreWrittenOnce(String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(REMOVED, ADDED);

        byte[] out = transformer.transformClass(tracker("java/lang/Object"), TRACKER);

        ClassNode node = read(out);
        assertEquals(List.of(ADDED, REMAP), node.interfaces,
                "the moved interface must collapse into the one already listed, not repeat it");
        // The JVM checks the header itself: a repeat is a ClassFormatError, a leftover Removed
        // is a NoClassDefFoundError.
        Class<?> loaded = load(out, Map.of(ADDED, iface(ADDED), REMAP, iface(REMAP)));
        assertEquals(2, loaded.getInterfaces().length);
    }

    @ParameterizedTest(name = "host {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("a rebased class still lists a collapsed interface once")
    void collapsedInterfacesSurviveSuperclassRebase(String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(REMOVED, ADDED);
        transformer.registerSuperclassRebase(OLD_BASE, NEW_BASE);

        byte[] out = transformer.transformClass(tracker(OLD_BASE), TRACKER);

        ClassNode node = read(out);
        assertEquals(NEW_BASE, node.superName, "the rebase must still apply");
        assertEquals(List.of(ADDED, REMAP), node.interfaces,
                "the rebase writes the header itself, and it must not bring the repeat back");
        load(out, Map.of(ADDED, iface(ADDED), REMAP, iface(REMAP), NEW_BASE, base(NEW_BASE)));
    }

    /** A class implementing Added, Remap and Removed, with a branch so it carries frames. */
    private static byte[] tracker(String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, TRACKER, null, superName, new String[] {ADDED, REMAP, REMOVED});
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor pick = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "pick", "(Z)I", null, null);
        pick.visitCode();
        Label second = new Label();
        pick.visitVarInsn(ILOAD, 0);
        pick.visitJumpInsn(IFEQ, second);
        pick.visitInsn(ICONST_1);
        pick.visitInsn(IRETURN);
        pick.visitLabel(second);
        pick.visitInsn(ICONST_2);
        pick.visitInsn(IRETURN);
        pick.visitMaxs(0, 0);
        pick.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] iface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] base(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Defines the transformed tracker beside {@code others}, so the JVM verifies its header. */
    private static Class<?> load(byte[] tracker, Map<String, byte[]> others) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(others);
        classes.put(TRACKER, tracker);
        ClassLoader loader = new ClassLoader(InterfaceCollapseTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
                byte[] bytes = classes.get(binaryName.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(binaryName);
                return defineClass(binaryName, bytes, 0, bytes.length);
            }
        };
        return Class.forName(TRACKER.replace('/', '.'), true, loader);
    }
}
