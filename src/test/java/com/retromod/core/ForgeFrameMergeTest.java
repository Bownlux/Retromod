/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Fabric pass has always given frame computation a way to read the mod's own class hierarchy;
 * the Forge and NeoForge pass did not, so the same branch between two of a mod's own types was
 * typed as {@code Object} there. Nobody had reported it, but it is the same defect that stopped
 * AutoClicky from starting on Fabric (#180), and the JVM rejects the method the same way.
 */
class ForgeFrameMergeTest {

    private static final String HOLDER = "com/example/mod/Holder";
    private static final String CHILD = "com/example/mod/Child";
    private static final String PARENT = "com/example/mod/Parent";
    /** The name the mod was built against, which the transform rewrites. */
    private static final String OLD_NAME = "net/minecraft/class_437";
    /** Stands for a Minecraft class: present at runtime, absent while transforming. */
    private static final String ABSENT = "net/minecraft/client/gui/screens/Screen";
    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    private static byte[] classWithSuper(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A class that branches between two of the mod's own types and then uses the result where the
     * absent supertype is required, which is exactly the shape that fails verification.
     */
    private static byte[] branchingHolder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOLDER, null, "java/lang/Object", null);

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pick", "(Z)L" + OLD_NAME + ";", null, null);
        m.visitCode();
        Label other = new Label();
        Label done = new Label();
        m.visitVarInsn(Opcodes.ILOAD, 0);
        m.visitJumpInsn(Opcodes.IFEQ, other);
        m.visitTypeInsn(Opcodes.NEW, CHILD);
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, CHILD, "<init>", "()V", false);
        m.visitJumpInsn(Opcodes.GOTO, done);
        m.visitLabel(other);
        m.visitTypeInsn(Opcodes.NEW, PARENT);
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, PARENT, "<init>", "()V", false);
        m.visitLabel(done);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void write(Path dir, String name, byte[] bytes) throws Exception {
        Path out = dir.resolve(name + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);
    }

    private static List<String> stackTypes(byte[] classBytes, String methodName) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);
        List<String> types = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (!methodName.equals(m.name)) continue;
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof FrameNode f && f.stack != null) {
                    for (Object o : f.stack) {
                        if (o instanceof String s) types.add(s);
                    }
                }
            }
        }
        return types;
    }

    @Test
    @DisplayName("Forge and NeoForge keep a mod's own shared type instead of widening to Object")
    void forgePassResolvesModOwnedMerge(@TempDir Path dir) throws Exception {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        write(dir, CHILD, classWithSuper(CHILD, PARENT));
        write(dir, PARENT, classWithSuper(PARENT, OLD_NAME));
        write(dir, HOLDER, branchingHolder());

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // One redirect is enough to make the pass actually re-emit and recompute frames.
        transformer.registerClassRedirect(OLD_NAME, ABSENT);

        new ForgeModTransformer("26.2").transformClasses(dir);

        List<String> after = stackTypes(Files.readAllBytes(dir.resolve(HOLDER + ".class")), "pick");
        assertFalse(after.contains("java/lang/Object"),
                "the Forge pass widened a merge of the mod's own types to Object, which the "
                        + "verifier rejects where the real supertype is required: " + after);
        assertTrue(after.contains(PARENT),
                "the shared type is in the mod's own jar and should have been read: " + after);
    }
}
