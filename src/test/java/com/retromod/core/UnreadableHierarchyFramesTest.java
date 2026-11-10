/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A translated class keeps javac's frames where its frames would join classes Retromod cannot read,
 * such as the loader's config values or a library's exceptions offline (Balm's NeoForge config and
 * Immersive Aircraft's data loaders failed verification on NeoForge 26.3).
 */
class UnreadableHierarchyFramesTest {

    // Library classes that are on the game's class path but not on Retromod's.
    private static final String CONFIG_VALUE = "probe/lib/ConfigValue";
    private static final String BOOLEAN_VALUE = "probe/lib/BooleanValue";
    private static final String PARSE_EXCEPTION = "probe/lib/JsonParseException";

    private String savedVersion;
    private RetromodTransformer transformer;

    @BeforeEach
    void target263() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // A frame-neutral rename, so the class is rewritten and its frames recomputed.
        transformer.registerFieldRedirect("probe/Flags", "field", "I", "probe/Flags", "renamed", "I");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void returnsAnUnreadableSubtypeThroughItsUnreadableBase() throws Exception {
        byte[] translated = transformer.transformClass(configBuilder(), "probe/ConfigBuilder");

        assertEquals("L" + CONFIG_VALUE + ";", stackAtMerge(translated, "define"),
                "the join of BooleanValue and ConfigValue is ConfigValue, as javac wrote it");
        assertVerifies(translated, "probe.ConfigBuilder");
    }

    @Test
    void catchesTwoExceptionsAsTheirSharedRuntimeException() throws Exception {
        byte[] translated = transformer.transformClass(dataLoader(), "probe/DataLoader");

        assertEquals("Ljava/lang/RuntimeException;", stackAtMerge(translated, "load"),
                "the handler for IllegalArgumentException and an unreadable JsonParseException "
                        + "receives a RuntimeException, not a Throwable");
        assertVerifies(translated, "probe.DataLoader");
    }

    @Test
    void reshapedMethodsKeepComputedFrames() {
        // A redirect that widens the return type inserts a cast, which moves later instructions.
        transformer.registerMethodRedirect("probe/Flags", "name", "()Ljava/lang/String;",
                "probe/Flags", "nameObject", "()Ljava/lang/Object;");
        byte[] translated = transformer.transformClass(configBuilder(), "probe/ConfigBuilder");

        assertFalse(("L" + CONFIG_VALUE + ";").equals(stackAtMerge(translated, "define")),
                "a method whose instructions moved cannot reuse javac's frames");
    }

    @Test
    void aFieldReadAtAnotherTypeKeepsComputedFrames() {
        // A plain field redirect to another descriptor leaves a value of another type on the stack.
        transformer.registerFieldRedirect("probe/Flags", "field", "I", "probe/Flags", "renamed", "F");
        byte[] translated = transformer.transformClass(configBuilder(), "probe/ConfigBuilder");

        assertFalse(("L" + CONFIG_VALUE + ";").equals(stackAtMerge(translated, "define")),
                "javac's frames were written for the old field type, so the method cannot reuse them");
    }

    @Test
    void preJava6ClassesWithSubroutinesKeepTheirRewrites() {
        transformer.registerMethodRedirect("probe/Flags", "name", "()Ljava/lang/String;",
                "probe/Flags", "nameObject", "()Ljava/lang/Object;");
        byte[] translated = transformer.transformClass(subroutineClass(), "probe/OldJetty");

        boolean[] redirected = {false};
        new ClassReader(translated).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called,
                            String desc, boolean isInterface) {
                        if (called.equals("nameObject")) redirected[0] = true;
                    }
                };
            }
        }, 0);
        assertTrue(redirected[0], "a Java 1.4 class needs no frames, so its JSR does not cost it the rewrite");
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** {@code define(boolean)} returns a BooleanValue on one branch and a ConfigValue on the other. */
    private static byte[] configBuilder() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/ConfigBuilder", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "define",
                "(Z)L" + CONFIG_VALUE + ";", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "probe/Flags", "field", "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "probe/Flags", "name", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        Label other = new Label();
        Label merged = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFEQ, other);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BOOLEAN_VALUE, "create", "()L" + BOOLEAN_VALUE + ";", false);
        mv.visitJumpInsn(Opcodes.GOTO, merged);
        mv.visitLabel(other);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONFIG_VALUE, "create", "()L" + CONFIG_VALUE + ";", false);
        mv.visitLabel(merged);
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{CONFIG_VALUE});
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code load()} catches IllegalArgumentException and JsonParseException in one handler and reads the message. */
    private static byte[] dataLoader() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/DataLoader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load",
                "()Ljava/lang/String;", null, null);
        mv.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/IllegalArgumentException");
        mv.visitTryCatchBlock(start, end, handler, PARSE_EXCEPTION);
        mv.visitLabel(start);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "probe/Flags", "field", "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(handler);
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/RuntimeException"});
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/RuntimeException", "getMessage",
                "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Java 1.4 class whose method calls a subroutine, as old javac compiled finally blocks. */
    private static byte[] subroutineClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "probe/OldJetty", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        Label subroutine = new Label();
        mv.visitJumpInsn(Opcodes.JSR, subroutine);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(subroutine);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "probe/Flags", "name", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.RET, 0);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- checks ----------------------------------------------------------------------------

    /** The single stack slot of the frame with one stack item in {@code method}. */
    private static String stackAtMerge(byte[] classBytes, String method) {
        String[] type = {null};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!method.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFrame(int frameType, int numLocal, Object[] local,
                            int numStack, Object[] stack) {
                        if (numStack == 1 && stack[0] instanceof String s) type[0] = "L" + s + ";";
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return type[0];
    }

    /** Links the class beside the library classes the game would have, so the JVM verifier runs. */
    private static void assertVerifies(byte[] translated, String className) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(className, translated);
        classes.put("probe.Flags", flags());
        classes.put(CONFIG_VALUE.replace('/', '.'), library(CONFIG_VALUE, "java/lang/Object"));
        classes.put(BOOLEAN_VALUE.replace('/', '.'), library(BOOLEAN_VALUE, CONFIG_VALUE));
        classes.put(PARSE_EXCEPTION.replace('/', '.'), library(PARSE_EXCEPTION, "java/lang/RuntimeException"));
        ClassLoader loader = new ClassLoader(UnreadableHierarchyFramesTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        // Reflection links the class, and linking runs the verifier.
        assertDoesNotThrow(() -> Class.forName(className, false, loader).getDeclaredMethods(),
                "the translated class must pass the JVM verifier");
    }

    private static byte[] library(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor create = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "()L" + name + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, name);
        create.visitInsn(Opcodes.DUP);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] flags() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Flags", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "renamed", "I", null, null).visitEnd();
        MethodVisitor name = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "name",
                "()Ljava/lang/String;", null, null);
        name.visitCode();
        name.visitLdcInsn("flags");
        name.visitInsn(Opcodes.ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
