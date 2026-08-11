/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for ordinary constructor calls being offered to fuzzy method resolution. */
class ConstructorHeuristicIsolationTest {

    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String TITLE_SCREEN = "net/minecraft/client/gui/screens/TitleScreen";

    @TempDir
    Path tempDir;

    @Test
    void fuzzyResolverNeverTreatsAConstructorAsAnOrdinaryMethod() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(writeTargetJar());

        assertNull(resolver.resolveMethod(TITLE_SCREEN, "<init>", "()V"),
                "TitleScreen.<init>() must not resolve to the same-shaped init() method");
    }

    @Test
    void transformerPreservesNewAndInvokespecialConstructorPair() throws Exception {
        RetromodTransformer transformer = newTransformer();
        transformer.registerClassRedirect("example/UnusedOld", "example/UnusedNew");
        transformer.initFuzzyResolver(writeTargetJar());

        byte[] transformed = transformer.transformClass(
                constructorProbe(), "example/ConstructorProbe");
        List<MethodCall> calls = methodCalls(transformed, "create");

        assertTrue(calls.contains(new MethodCall(
                        Opcodes.INVOKESPECIAL, TITLE_SCREEN, "<init>", "()V")),
                "the constructor invocation must remain attached to the NEW TitleScreen value");
        assertFalse(calls.stream().anyMatch(call -> "init".equals(call.name())),
                "constructor resolution must not emit an ordinary init() call");
    }

    private Path writeTargetJar() throws Exception {
        Path jar = tempDir.resolve("target-minecraft.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeClass(out, SCREEN, screenClass());
            writeClass(out, TITLE_SCREEN, titleScreenClass());
        }
        return jar;
    }

    private static void writeClass(JarOutputStream out, String name, byte[] bytes)
            throws Exception {
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] screenClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                SCREEN, null, "java/lang/Object", null);
        addConstructor(writer, SCREEN, "java/lang/Object");
        addInitMethod(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] titleScreenClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TITLE_SCREEN, null, SCREEN, null);
        addConstructor(writer, TITLE_SCREEN, SCREEN);
        addInitMethod(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addConstructor(ClassWriter writer, String owner, String superOwner) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, superOwner, "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void addInitMethod(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PROTECTED, "init", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] constructorProbe() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "example/ConstructorProbe", null, "java/lang/Object", null);
        addConstructor(writer, "example/ConstructorProbe", "java/lang/Object");

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "create", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, TITLE_SCREEN);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, TITLE_SCREEN, "<init>", "()V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static RetromodTransformer newTransformer() throws Exception {
        Constructor<RetromodTransformer> constructor =
                RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static List<MethodCall> methodCalls(byte[] classBytes, String methodName) {
        List<MethodCall> calls = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!methodName.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        calls.add(new MethodCall(opcode, owner, name, descriptor));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    private record MethodCall(int opcode, String owner, String name, String descriptor) {}
}
