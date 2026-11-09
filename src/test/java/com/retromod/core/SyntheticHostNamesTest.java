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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** An embedded synthetic keeps a name the host has again, which a version-limited redirect sends only older mods elsewhere. */
class SyntheticHostNamesTest {

    private static final String SYNTHETIC = "com/retromod/legacyapi/test/HostLoaderAdapter";
    // Forge 26.2 kept IGeometryLoader's name with a new shape; older mods' references go to a rebuilt class.
    private static final String HOST_KEPT = "net/minecraftforge/client/model/geometry/IGeometryLoader";
    private static final String REBUILT = "com/retromod/legacyapi/test/LegacyGeometryLoader";
    private static final String MOVED_OLD = "com/mojang/blaze3d/PrimitiveTopology";
    private static final String MOVED_NEW = "com/mojang/renderpearl/api/pipeline/PrimitiveTopology";
    private static final String MOD_CLASS = "mod/UsesAdapter";

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.setModSourceVersion(null);
        t.clearRedirectsForTesting();
    }

    private static RetromodTransformer prepared() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        t.registerSyntheticClass(SYNTHETIC, adapter());
        t.registerSyntheticClass(REBUILT, interfaceClass(REBUILT));
        t.registerClassRedirect(HOST_KEPT, REBUILT);
        t.limitClassRedirectToModsBefore(HOST_KEPT, "26.2");
        t.registerClassRedirect(MOVED_OLD, MOVED_NEW);
        // An old mod, which takes the limited redirect for its own classes.
        t.setModSourceVersion("1.20.1");
        return t;
    }

    @Test
    @DisplayName("Embedding into jar bytes, a jar file and an extracted folder keeps the host's name and follows plain moves")
    void everyEmbeddingPathKeepsTheHostName() throws Exception {
        RetromodTransformer t = prepared();
        SyntheticEmbedder.ByteEmbeddingResult bytes = SyntheticEmbedder.embedIntoJarBytes(modJar(), "testmod", t);
        assertTrue(bytes.succeeded());
        assertKeepsHostName(adapterIn(bytes.jarBytes()), "jar bytes");

        Path jar = dir.resolve("mod.jar");
        Files.write(jar, modJar());
        assertTrue(SyntheticEmbedder.embedIntoJar(jar, "testmod", t) > 0);
        assertKeepsHostName(adapterIn(Files.readAllBytes(jar)), "jar file");

        Path folder = Files.createDirectories(dir.resolve("extracted"));
        Files.createDirectories(folder.resolve("mod"));
        Files.write(folder.resolve(MOD_CLASS + ".class"), modClass());
        assertTrue(SyntheticEmbedder.embed(folder, "testmod", t) > 0);
        ClassNode adapter = null;
        try (var files = Files.walk(folder)) {
            for (Path file : files.filter(f -> f.toString().endsWith("HostLoaderAdapter.class")).toList()) {
                adapter = node(Files.readAllBytes(file));
            }
        }
        assertNotNull(adapter, "the adapter was embedded");
        assertKeepsHostName(adapter, "extracted folder");
    }

    private static void assertKeepsHostName(ClassNode adapter, String path) {
        assertEquals(List.of(HOST_KEPT), adapter.interfaces, path + ": the adapter implements the host's interface");
        String desc = adapter.methods.stream().filter(m -> m.name.equals("topology")).findFirst().orElseThrow().desc;
        assertEquals("()L" + MOVED_NEW + ";", desc, path + ": a plain class move still applies");
    }

    private static ClassNode adapterIn(byte[] jar) throws Exception {
        Path file = Files.createTempFile("embedded-", ".jar");
        try {
            Files.write(file, jar);
            try (ZipFile zip = new ZipFile(file.toFile())) {
                List<String> names = new ArrayList<>();
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    names.add(entry.getName());
                    if (entry.getName().endsWith("HostLoaderAdapter.class")) {
                        return node(zip.getInputStream(entry).readAllBytes());
                    }
                }
                throw new AssertionError("no embedded adapter in " + names);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Retromod's adapter, written for the host: it implements the host's interface and names a class 26.3 moved. */
    private static byte[] adapter() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SYNTHETIC, null, "java/lang/Object", new String[]{HOST_KEPT});
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "topology", "()L" + MOVED_OLD + ";", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] interfaceClass(String name) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        w.visitEnd();
        return w.toByteArray();
    }

    /** An old mod class that uses the adapter, so the embedder pulls it in. */
    private static byte[] modClass() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, SYNTHETIC, "topology", "()L" + MOVED_OLD + ";", false);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] modJar() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(MOD_CLASS + ".class"));
            jar.write(modClass());
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}
