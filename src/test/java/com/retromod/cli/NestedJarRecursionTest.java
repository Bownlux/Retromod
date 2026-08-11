package com.retromod.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import com.retromod.core.RetromodTransformer;

/**
 * §C JiJ-recursion (#95). A mod that bundles a library in {@code META-INF/jars/} (Fabric) or
 * {@code META-INF/jarjar/} (NeoForge) needs that library's bytecode transformed too. A lib
 * referencing a relocated class otherwise loads broken or is reported "missing". This drives
 * {@link RetromodCli#transformNestedJar} directly and proves it (a) rewrites a nested jar's
 * own classes and (b) recurses into a jar-inside-a-jar.
 */
class NestedJarRecursionTest {

    private static final String OLD = "test/relocated/Old";
    private static final String NEW = "test/relocated/New";

    /** A class with a field typed {@code Ltest/relocated/Old;}, a reference the redirect rewrites. */
    private static byte[] classRef(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "ref", "L" + OLD + ";", null, null).visitEnd();
        var c = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(1, 1);
        c.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] jarOf(java.util.Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            for (var e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static byte[] entry(byte[] jar, String name) throws Exception {
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry e;
            while ((e = jis.getNextJarEntry()) != null) {
                if (e.getName().equals(name)) return jis.readAllBytes();
            }
        }
        return null;
    }

    /** Field "ref"'s descriptor on the class at entryName inside jar. */
    private static String refDesc(byte[] jar, String entryName) throws Exception {
        byte[] cls = entry(jar, entryName);
        ClassNode cn = new ClassNode();
        new ClassReader(cls).accept(cn, 0);
        for (FieldNode f : cn.fields) {
            if (f.name.equals("ref")) return f.desc;
        }
        throw new AssertionError("no 'ref' field on " + entryName);
    }

    @Test
    @DisplayName("nested jar bytecode is transformed, and recursion reaches a jar-inside-a-jar")
    void nestedAndDoublyNestedClassesTransformed() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerClassRedirect(OLD, NEW);

            // A doubly-nested jar (jar inside the lib's own META-INF/jarjar/).
            byte[] deepJar = jarOf(java.util.Map.of("deep/Deep.class", classRef("deep/Deep")));
            // The library jar: its own class + the doubly-nested jar.
            byte[] libJar = jarOf(java.util.Map.of(
                    "lib/Lib.class", classRef("lib/Lib"),
                    "META-INF/jarjar/deep.jar", deepJar));

            byte[] out = RetromodCli.transformNestedJar(libJar, 1);

            // (a) the library's own class was rewritten Old -> New
            assertEquals("L" + NEW + ";", refDesc(out, "lib/Lib.class"),
                    "nested library class must be transformed");
            // (b) recursion reached the jar-inside-the-jar
            byte[] deepOut = entry(out, "META-INF/jarjar/deep.jar");
            assertEquals("L" + NEW + ";", refDesc(deepOut, "deep/Deep.class"),
                    "doubly-nested jar must be recursed into and transformed");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("nested Forge refmaps use the same selector redirects as the outer mod")
    void nestedForgeRefmapIsTransformed() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            String oldOwner = "net/minecraft/block/PortalSize";
            String newOwner = "net/minecraft/world/level/portal/PortalShape";
            transformer.registerClassRedirect(oldOwner, newOwner);
            transformer.registerMethodRedirect(
                    oldOwner, "func_242974_d", "()I",
                    newOwner, "m_77745_", "()I");

            String refmap = "{\"mappings\":{\"MixinPortal\":{\"func_242974_d()I\":"
                    + "\"L" + oldOwner + ";func_242974_d()I\"}}}";
            byte[] nested = jarOf(java.util.Map.of(
                    "portal-refmap.json", refmap.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            String transformed = new String(
                    entry(RetromodCli.transformNestedJar(nested, 1, true), "portal-refmap.json"),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(transformed.contains("L" + newOwner + ";m_77745_()I"),
                    "the nested refmap value must use the target owner and member");
            assertTrue(transformed.contains("\"m_77745_()I\""),
                    "the descriptor-shaped refmap key must follow the member redirect");
            assertFalse(transformed.contains("func_242974_d"),
                    "the source SRG selector must not survive in a nested refmap");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }
}
