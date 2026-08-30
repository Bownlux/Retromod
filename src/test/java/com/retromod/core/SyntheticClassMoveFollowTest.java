/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A synthetic is compiled against the Minecraft version Retromod was built for, so its own
 * references age exactly like a mod's do. Minecraft 26.3 moved
 * {@code com/mojang/blaze3d/PrimitiveTopology} into {@code com/mojang/renderpearl}, and a render
 * synthetic that still named the old owner failed to link on 26.3 even when the mod around it had
 * been translated correctly.
 *
 * <p>Three embedding paths build the relocation remapper, so each is checked here: they drifted
 * apart once already.
 */
class SyntheticClassMoveFollowTest {

    private static final String SYNTHETIC = "com/retromod/shim/common/embedded/TestBufferSource";
    private static final String MOVED_OLD = "com/mojang/blaze3d/PrimitiveTopology";
    private static final String MOVED_NEW = "com/mojang/renderpearl/api/pipeline/PrimitiveTopology";
    private static final String MOD_CLASS = "mod/UsesSynthetic";

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRedirects() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    /** A transformer that knows the synthetic and the 26.3 class move. */
    private static RetromodTransformer prepared() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        t.registerSyntheticClass(SYNTHETIC, syntheticReferencingMovedClass());
        t.registerClassRedirect(MOVED_OLD, MOVED_NEW);
        return t;
    }

    /** A synthetic whose body names the class 26.3 moved. */
    private static byte[] syntheticReferencingMovedClass() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SYNTHETIC, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "topology", "()L" + MOVED_OLD + ";", null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, MOVED_OLD, "QUADS", "L" + MOVED_OLD + ";");
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A mod class that calls the synthetic, so the embedder pulls it in. */
    private static byte[] modClass() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, SYNTHETIC, "topology",
                "()L" + MOVED_OLD + ";", false);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] modJarBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(MOD_CLASS + ".class"));
            jar.write(modClass());
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    /** Whether the embedded output still names the pre-move owner anywhere. */
    private static void assertFollowedTheMove(byte[] jarBytes, String path) throws IOException {
        Path jar = Path.of(path);
        Files.write(jar, jarBytes);
        boolean sawNew = false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                String body;
                try (var in = zip.getInputStream(e)) {
                    body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                assertFalse(Pattern.compile(Pattern.quote(MOVED_OLD)).matcher(body).find(),
                        e.getName() + " still names the class 26.3 moved");
                if (body.contains(MOVED_NEW)) sawNew = true;
            }
        }
        assertTrue(sawNew, "the embedded synthetic must name the class's new home");
    }

    @Test
    @DisplayName("Embedding into a jar path follows the move")
    void jarPathFollowsTheMove() throws Exception {
        RetromodTransformer t = prepared();
        Path jar = tempDir.resolve("mod.jar");
        Files.write(jar, modJarBytes());

        assertTrue(SyntheticEmbedder.embedIntoJar(jar, "testmod", t) > 0,
                "the synthetic is referenced, so it must be embedded");
        assertFollowedTheMove(Files.readAllBytes(jar), tempDir.resolve("check1.jar").toString());
    }

    @Test
    @DisplayName("Embedding into jar bytes follows the move")
    void bytePathFollowsTheMove() throws Exception {
        RetromodTransformer t = prepared();
        SyntheticEmbedder.ByteEmbeddingResult result =
                SyntheticEmbedder.embedIntoJarBytes(modJarBytes(), "testmod", t);
        assertTrue(result.succeeded());
        assertTrue(result.embeddedCount() > 0);
        byte[] out = result.jarBytes();
        assertFollowedTheMove(out, tempDir.resolve("check2.jar").toString());
    }

    @Test
    @DisplayName("Embedding into an unpacked directory follows the move")
    void directoryPathFollowsTheMove() throws Exception {
        RetromodTransformer t = prepared();
        Path dir = tempDir.resolve("unpacked");
        Path cls = dir.resolve(MOD_CLASS + ".class");
        Files.createDirectories(cls.getParent());
        Files.write(cls, modClass());

        assertTrue(SyntheticEmbedder.embed(dir, "testmod", t) > 0);

        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".class")).toList()) {
                String body = new String(Files.readAllBytes(p),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(body.contains(MOVED_OLD),
                        p.getFileName() + " still names the class 26.3 moved");
            }
        }
    }
}
