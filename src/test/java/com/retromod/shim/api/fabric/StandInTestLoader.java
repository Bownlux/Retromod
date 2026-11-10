/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Defines generated stand-ins next to empty Minecraft classes, so a test can call them. */
final class StandInTestLoader extends ClassLoader {

    private final Map<String, byte[]> classes = new HashMap<>();

    StandInTestLoader() {
        super(StandInTestLoader.class.getClassLoader());
    }

    void add(String internalName, byte[] bytes) {
        classes.put(internalName.replace('/', '.'), bytes);
    }

    /** Adds an empty public class whose {@code toString} returns {@code text}. */
    void addMinecraftClass(String internalName, String text) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor toString = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        toString.visitCode();
        toString.visitLdcInsn(text);
        toString.visitInsn(Opcodes.ARETURN);
        toString.visitMaxs(0, 0);
        toString.visitEnd();
        cw.visitEnd();
        add(internalName, cw.toByteArray());
    }

    Class<?> load(String internalName) throws ClassNotFoundException {
        return loadClass(internalName.replace('/', '.'));
    }

    // Classes added here win over the parent, which has Retromod's own compile stub of class_2960.
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && classes.containsKey(name)) {
                byte[] bytes = classes.get(name);
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (loaded == null) return super.loadClass(name, resolve);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Runs {@code action} and returns what it wrote to standard error, where the test logger writes. */
    static String captureErr(ThrowingRunnable action) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
