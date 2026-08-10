/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.aot.AotCompiler;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedJarFrameMergeTest {

    private static final String HOLDER = "nested/frame/Holder";
    private static final String CHILD = "nested/frame/Child";
    private static final String PARENT = "nested/frame/Parent";
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
    void nestedJarUsesItsOwnHierarchyWhenRebuildingFrames() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_SCREEN, NEW_SCREEN);

        byte[] transformedJar = RetromodCli.transformNestedJar(frameJar(), 1);
        List<String> frameTypes = frameStackTypes(entry(transformedJar, HOLDER + ".class"));

        assertTrue(frameTypes.contains(PARENT),
                "the nested library's shared parent must remain in the rebuilt frame: "
                        + frameTypes);
        assertFalse(frameTypes.contains("java/lang/Object"),
                "the nested library hierarchy must not be widened to Object: " + frameTypes);
    }

    @Test
    void aotNestedJarUsesItsOwnHierarchyWhenRebuildingFrames() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_SCREEN, NEW_SCREEN);

        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method transformNested = AotCompiler.class.getDeclaredMethod("transformNestedJarAot",
                byte[].class, int.class, MixinCompatibilityTransformer.class);
        transformNested.setAccessible(true);
        byte[] transformedJar = (byte[]) transformNested.invoke(compiler, frameJar(), 1,
                new MixinCompatibilityTransformer(transformer));
        List<String> frameTypes = frameStackTypes(entry(transformedJar, HOLDER + ".class"));

        assertTrue(frameTypes.contains(PARENT),
                "AOT nested jars must retain their mod-owned shared parent: " + frameTypes);
        assertFalse(frameTypes.contains("java/lang/Object"),
                "AOT nested jars must not widen the branch to Object: " + frameTypes);
    }

    private static byte[] frameJar() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(HOLDER + ".class", branchingHolder());
        entries.put(CHILD + ".class", classWithSuper(CHILD, PARENT));
        entries.put(PARENT + ".class", classWithSuper(PARENT, OLD_SCREEN));
        return jar(entries);
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

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] entry(byte[] jarBytes, String name) throws Exception {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (name.equals(entry.getName())) return jar.readAllBytes();
            }
        }
        throw new AssertionError("missing nested entry " + name);
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
