/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The per-mod synthetic-embedding engine. Verifies the split-package safety invariants
 * NeoForge's JPMS module-per-mod loading needs:
 * <ul>
 *   <li>a synthetic is embedded only into a mod that references it;</li>
 *   <li>it goes under a unique-per-mod {@code com/retromod/embedded/<key>/} package, not at its
 *       original loader-owned name, so it can't split-package with the loader or another mod;</li>
 *   <li>the mod's references are rewritten to the embedded copy;</li>
 *   <li>a mod that doesn't reference it is left untouched.</li>
 * </ul>
 */
class SyntheticEmbedderTest {

    private static final String SYNTH = "net/fake/loaderpkg/Removed"; // a deleted loader class
    private static final String SECOND_SYNTH = "net/fake/otherpkg/Removed";

    private static byte[] simpleClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);
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

    /** A class with a field typed {@code L<SYNTH>;} for the embedder to rewrite. */
    private static byte[] classReferencing(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "ref", "L" + SYNTH + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classReferencingNothing(String name) {
        return simpleClass(name);
    }

    private static byte[] classReferencingBoth(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "first", "L" + SYNTH + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "second", "L" + SECOND_SYNTH + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void write(Path dir, String internalName, byte[] bytes) throws Exception {
        Path p = dir.resolve(internalName + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    private static String fieldDesc(Path dir, String internalName) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(dir.resolve(internalName + ".class"))).accept(cn, 0);
        for (FieldNode f : cn.fields) if (f.name.equals("ref")) return f.desc;
        return null;
    }

    @Test
    @DisplayName("referenced synthetic is embedded under a unique-per-mod package, NOT its original name")
    void embedsReferencedSyntheticSafely(@TempDir Path dir) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            write(dir, "mod/Uses", classReferencing("mod/Uses"));
            write(dir, "mod/Other", classReferencingNothing("mod/Other"));

            int n = SyntheticEmbedder.embed(dir, "cool-mod.jar", t);

            assertEquals(1, n, "the one referenced synthetic should be embedded");
            String uniquePkg = SyntheticEmbedder.embeddedBase("cool-mod.jar") + SYNTH;
            assertTrue(Files.exists(dir.resolve(uniquePkg + ".class")),
                    "synthetic must be embedded under com/retromod/embedded/<key>/");
            assertFalse(Files.exists(dir.resolve(SYNTH + ".class")),
                    "synthetic must NOT be embedded at its original (loader) package name");
            assertEquals("L" + uniquePkg + ";", fieldDesc(dir, "mod/Uses"),
                    "the referencing class must now point at the embedded copy");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("a mod that references no synthetic is left untouched")
    void noReferenceNoEmbed(@TempDir Path dir) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            write(dir, "mod/Plain", classReferencingNothing("mod/Plain"));
            byte[] before = Files.readAllBytes(dir.resolve("mod/Plain.class"));

            int n = SyntheticEmbedder.embed(dir, "plain-mod.jar", t);

            assertEquals(0, n, "nothing referenced -> nothing embedded");
            assertFalse(Files.exists(dir.resolve(
                    SyntheticEmbedder.embeddedBase("plain-mod.jar") + SYNTH + ".class")));
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("mod/Plain.class")),
                    "a non-referencing class must be byte-for-byte unchanged");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("two mods get the synthetic in DISTINCT packages (no cross-mod split-package)")
    void distinctPackagesPerMod(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            write(dirA, "a/Uses", classReferencing("a/Uses"));
            write(dirB, "b/Uses", classReferencing("b/Uses"));

            SyntheticEmbedder.embed(dirA, "mod-a.jar", t);
            SyntheticEmbedder.embed(dirB, "mod-b.jar", t);

            assertTrue(Files.exists(dirA.resolve(
                    SyntheticEmbedder.embeddedBase("mod-a.jar") + SYNTH + ".class")));
            assertTrue(Files.exists(dirB.resolve(
                    SyntheticEmbedder.embeddedBase("mod-b.jar") + SYNTH + ".class")));
            assertFalse(Files.exists(dirA.resolve(
                    SyntheticEmbedder.embeddedBase("mod-b.jar") + SYNTH + ".class")));
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("embedIntoJar (offline path): embeds + rewrites in-place and preserves the manifest")
    void embedIntoJarPreservesManifest(@TempDir Path dir) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            Path jar = dir.resolve("mod.jar");
            Path oldPredictableTemp = dir.resolve("mod.jar.rmtmp");
            Files.writeString(oldPredictableTemp, "belongs to another process");
            try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                zos.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
                zos.write("Manifest-Version: 1.0\r\nFabric-Loom-Version: x\r\n\r\n".getBytes());
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("mod/Uses.class"));
                zos.write(classReferencing("mod/Uses"));
                zos.closeEntry();
            }

            int n = SyntheticEmbedder.embedIntoJar(jar, "cool-mod.jar", t);
            assertEquals(1, n);

            java.util.Map<String, byte[]> out = new java.util.HashMap<>();
            try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(jar))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (!e.isDirectory()) out.put(e.getName(), zis.readAllBytes());
                }
            }
            assertTrue(out.containsKey("META-INF/MANIFEST.MF"), "manifest must be preserved");
            assertTrue(new String(out.get("META-INF/MANIFEST.MF")).contains("Manifest-Version: 1.0"),
                    "manifest content intact");
            String embeddedName = SyntheticEmbedder.embeddedBase("cool-mod.jar") + SYNTH;
            assertTrue(out.containsKey(embeddedName + ".class"),
                    "synthetic embedded under unique pkg");
            assertFalse(out.containsKey(SYNTH + ".class"), "not at original (loader) name");
            ClassNode cn = new ClassNode();
            new ClassReader(out.get("mod/Uses.class")).accept(cn, 0);
            assertEquals("L" + embeddedName + ";", cn.fields.get(0).desc,
                    "reference rewritten to the embedded copy");
            assertEquals("belongs to another process", Files.readString(oldPredictableTemp),
                    "embedding must use a unique temporary file");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("sanitized key collisions still receive distinct stable package names")
    void rawKeyDigestPreventsPackageCollisions() {
        String dashed = SyntheticEmbedder.embeddedBase("same-mod.jar");
        String underscored = SyntheticEmbedder.embeddedBase("same_mod.jar");

        assertNotEquals(dashed, underscored,
                "the raw key digest must distinguish keys with the same sanitized spelling");
        assertEquals(dashed, SyntheticEmbedder.embeddedBase("same-mod.jar"),
                "the package name must remain stable for repeat transforms");
    }

    @Test
    @DisplayName("synthetics with the same simple name retain their original relative paths")
    void simpleNameCollisionDoesNotOverwriteSynthetic(@TempDir Path dir) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            t.registerSyntheticClass(SECOND_SYNTH, simpleClass(SECOND_SYNTH));
            write(dir, "mod/UsesBoth", classReferencingBoth("mod/UsesBoth"));

            assertEquals(2, SyntheticEmbedder.embed(dir, "collision.jar", t));
            String base = SyntheticEmbedder.embeddedBase("collision.jar");
            assertTrue(Files.exists(dir.resolve(base + SYNTH + ".class")));
            assertTrue(Files.exists(dir.resolve(base + SECOND_SYNTH + ".class")));

            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(dir.resolve("mod/UsesBoth.class"))).accept(node, 0);
            assertEquals("L" + base + SYNTH + ";", node.fields.get(0).desc);
            assertEquals("L" + base + SECOND_SYNTH + ";", node.fields.get(1).desc);
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("an existing generated class path is never overwritten in an extracted mod")
    void extractedSyntheticCollisionLeavesClassesUntouched(@TempDir Path dir) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            write(dir, "mod/Uses", classReferencing("mod/Uses"));
            String generatedName = SyntheticEmbedder.embeddedBase("collision.jar") + SYNTH;
            byte[] existing = simpleClass(generatedName);
            write(dir, generatedName, existing);
            byte[] usesBefore = Files.readAllBytes(dir.resolve("mod/Uses.class"));

            assertEquals(0, SyntheticEmbedder.embed(dir, "collision.jar", t));
            assertArrayEquals(existing, Files.readAllBytes(dir.resolve(generatedName + ".class")));
            assertArrayEquals(usesBefore, Files.readAllBytes(dir.resolve("mod/Uses.class")),
                    "collision validation must happen before references are rewritten");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("an existing generated jar entry is never overwritten")
    void jarSyntheticCollisionLeavesOriginalJarUntouched(@TempDir Path dir) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            Path jar = dir.resolve("collision.jar");
            String generatedName = SyntheticEmbedder.embeddedBase("collision.jar") + SYNTH;
            try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                output.putNextEntry(new java.util.zip.ZipEntry("mod/Uses.class"));
                output.write(classReferencing("mod/Uses"));
                output.closeEntry();
                output.putNextEntry(new java.util.zip.ZipEntry(generatedName + ".class"));
                output.write(simpleClass(generatedName));
                output.closeEntry();
            }
            byte[] original = Files.readAllBytes(jar);

            assertEquals(0, SyntheticEmbedder.embedIntoJar(jar, "collision.jar", t));
            assertArrayEquals(original, Files.readAllBytes(jar));
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("unsafe jar entry names abort without replacing the original jar")
    void unsafeEntryLeavesOriginalJarUntouched(@TempDir Path dir) throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            t.registerSyntheticClass(SYNTH, simpleClass(SYNTH));
            Path jar = dir.resolve("unsafe.jar");
            try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                zos.putNextEntry(new java.util.zip.ZipEntry("../outside.txt"));
                zos.write("unsafe".getBytes());
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("mod/Uses.class"));
                zos.write(classReferencing("mod/Uses"));
                zos.closeEntry();
            }
            byte[] original = Files.readAllBytes(jar);

            assertEquals(0, SyntheticEmbedder.embedIntoJar(jar, "unsafe.jar", t));
            assertArrayEquals(original, Files.readAllBytes(jar),
                    "validation must finish before a replacement jar is written");
            assertFalse(Files.exists(dir.resolve("outside.txt")));
        } finally {
            t.clearRedirectsForTesting();
        }
    }
}
