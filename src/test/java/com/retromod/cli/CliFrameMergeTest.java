/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.embedder.ModVersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliFrameMergeTest {

    private static final String HOLDER = "cli/frame/Holder";
    private static final String CHILD = "cli/frame/Child";
    private static final String PARENT = "cli/frame/Parent";
    private static final String OLD_SCREEN = "net/minecraft/class_437";
    private static final String NEW_SCREEN = "net/minecraft/client/gui/screens/Screen";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @Test
    void transformCommandUsesTheInputJarsHierarchy(@TempDir Path dir) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_SCREEN, NEW_SCREEN);

        Path input = dir.resolve("input.jar");
        Path output = dir.resolve("output.jar");
        writeJar(input);
        ModVersionInfo info = new ModVersionInfo("framecli", "1.0", "1.21.1", "fabric",
                null, Set.of("cli/frame/"), Set.of(), false);

        Method transformJar = RetromodCli.class.getDeclaredMethod("transformJar",
                Path.class, Path.class, RetromodTransformer.class, ModVersionInfo.class);
        transformJar.setAccessible(true);
        transformJar.invoke(null, input, output, transformer, info);

        List<String> frameTypes = frameStackTypes(readEntry(output, HOLDER + ".class"));
        assertTrue(frameTypes.contains(PARENT),
                "the CLI must retain the mod-owned shared parent: " + frameTypes);
        assertFalse(frameTypes.contains("java/lang/Object"),
                "the CLI must not widen the mod-owned branch to Object: " + frameTypes);
    }

    private static void writeJar(Path path) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            writeEntry(jar, HOLDER + ".class", branchingHolder());
            writeEntry(jar, CHILD + ".class", classWithSuper(CHILD, PARENT));
            writeEntry(jar, PARENT + ".class", classWithSuper(PARENT, OLD_SCREEN));
        }
    }

    private static void writeEntry(JarOutputStream jar, String name, byte[] bytes)
            throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static byte[] readEntry(Path jarPath, String name) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(name);
            if (entry == null) throw new AssertionError("missing " + name);
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static byte[] classWithSuper(String name, String superName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor ctor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] branchingHolder() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOLDER,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pick", "(Z)L" + OLD_SCREEN + ";", null, null);
        method.visitCode();
        Label parent = new Label();
        Label done = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, parent);
        method.visitTypeInsn(Opcodes.NEW, CHILD);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, CHILD, "<init>", "()V", false);
        method.visitJumpInsn(Opcodes.GOTO, done);
        method.visitLabel(parent);
        method.visitTypeInsn(Opcodes.NEW, PARENT);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, PARENT, "<init>", "()V", false);
        method.visitLabel(done);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> frameStackTypes(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        List<String> types = new ArrayList<>();
        node.methods.stream().filter(method -> "pick".equals(method.name)).forEach(method -> {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof FrameNode frame && frame.stack != null) {
                    for (Object type : frame.stack) {
                        if (type instanceof String name) types.add(name);
                    }
                }
            }
        });
        return types;
    }
}
