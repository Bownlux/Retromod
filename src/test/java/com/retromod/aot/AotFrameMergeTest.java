/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.ShimRegistry;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A precompiled result is written to a cache and reused across launches, so a frame rebuilt without
 * the mod's own class hierarchy would leave a mod permanently unable to start. Same defect as #180,
 * with a longer shelf life.
 */
class AotFrameMergeTest {

    private static final String HOLDER = "com/example/mod/Holder";
    private static final String CHILD = "com/example/mod/Child";
    private static final String PARENT = "com/example/mod/Parent";
    private static final String OLD_NAME = "net/minecraft/class_437";
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

    /** Branches between two of the mod's own types, then returns the shared supertype. */
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

    /** A minimal Fabric mod built for an older version, so the precompiler has work to do. */
    private static Path buildModJar(Path dir) throws Exception {
        Path jar = dir.resolve("framemerge-test-mod.jar");
        String meta = "{\"schemaVersion\":1,\"id\":\"framemerge\",\"version\":\"1.0.0\","
                + "\"depends\":{\"minecraft\":\"1.21.1\"}}";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("fabric.mod.json"));
            out.write(meta.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (String[] c : new String[][]{
                    {CHILD, PARENT}, {PARENT, OLD_NAME}}) {
                out.putNextEntry(new ZipEntry(c[0] + ".class"));
                out.write(classWithSuper(c[0], c[1]));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(HOLDER + ".class"));
            out.write(branchingHolder());
            out.closeEntry();
        }
        return jar;
    }

    private static List<String> stackTypes(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);
        List<String> types = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (!"pick".equals(m.name)) continue;
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
    @DisplayName("A precompiled mod keeps its own shared type instead of caching Object")
    void precompilerResolvesModOwnedMerge(@TempDir Path dir) throws Exception {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Path modJar = buildModJar(dir);

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_NAME, ABSENT);
        transformer.addTransformablePackage("com/example/mod/");

        // A stale cache entry from an earlier run would be served instead of a fresh compile.
        Path cached = Path.of("config/retromod/aot-cache")
                .resolve(modJar.getFileName().toString().replace(".jar", "-aot.jar"));
        Files.deleteIfExists(cached);

        Path prepared = new AotCompiler(new ShimRegistry(), "26.2").compileModAot(modJar);
        assertNotEquals(modJar, prepared, "the fixture must actually be precompiled");
        try (ZipFile zip = new ZipFile(prepared.toFile())) {
            ZipEntry e = zip.getEntry(HOLDER + ".class");
            assertNotNull(e, "the precompiled jar should still carry the class");
            byte[] out;
            try (InputStream in = zip.getInputStream(e)) {
                out = in.readAllBytes();
            }
            List<String> types = stackTypes(out);
            assertFalse(types.contains("java/lang/Object"),
                    "the precompiler cached a merge widened to Object: " + types);
        } finally {
            Files.deleteIfExists(prepared);
        }
    }
}
