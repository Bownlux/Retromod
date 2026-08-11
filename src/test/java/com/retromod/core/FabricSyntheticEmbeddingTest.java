/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that Fabric packaging keeps generated compatibility classes private to each mod. */
class FabricSyntheticEmbeddingTest {

    private static final String SYNTHETIC = "com/retromod/generated/fixture/Helper";
    private static final String UNUSED = "com/retromod/generated/fixture/Unused";
    private static final String FIXTURE = "fixture/UsesHelper";
    private static final String NESTED_FIXTURE = "nested/UsesHelper";

    @Test
    @DisplayName("Fabric packaging relocates referenced synthetics and omits shared copies")
    void packagesOnlyPerModSyntheticCopies(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC));
            transformer.registerSyntheticClass(UNUSED, emptyClass(UNUSED));

            Path extracted = dir.resolve("extracted");
            writeClass(extracted, FIXTURE, classReferencingSynthetic());
            Path original = dir.resolve("legacy-events.jar");
            writeManifestJar(original);
            Path output = dir.resolve("legacy-events-retromod.jar");

            invokeRepackage(new FabricModTransformer("26.2"), extracted, output, original);

            String base = SyntheticEmbedder.embeddedBase(original.getFileName().toString());
            String relocated = base + SYNTHETIC;
            try (JarFile jar = new JarFile(output.toFile())) {
                assertNotNull(jar.getJarEntry(relocated + ".class"),
                        "the referenced helper must use a jar-specific class name");
                assertNotNull(jar.getJarEntry(base),
                        "the relocated package must keep its directory resource entries");
                assertFalse(jar.stream().anyMatch(entry ->
                                entry.getName().equals(SYNTHETIC + ".class")),
                        "Fabric must not publish a shared synthetic class into Knot");
                assertFalse(jar.stream().anyMatch(entry ->
                                entry.getName().endsWith(UNUSED + ".class")),
                        "unreferenced synthetics must not be copied into every mod");

                ClassNode fixture = new ClassNode();
                try (var input = jar.getInputStream(jar.getJarEntry(FIXTURE + ".class"))) {
                    new ClassReader(input.readAllBytes()).accept(fixture, 0);
                }
                assertEquals("L" + relocated + ";", fixture.fields.get(0).desc,
                        "the mod class must reference its relocated helper");
            }
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("Fabric packaging relocates synthetics inside nested JiJ mods")
    void packagesNestedSyntheticCopiesWithStablePerEntryName(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC));

            Path extracted = dir.resolve("extracted");
            Path nested = extracted.resolve("META-INF/jars/library.jar");
            Files.createDirectories(nested.getParent());
            writeNestedFixture(nested);

            Path original = dir.resolve("outer-mod.jar");
            writeManifestJar(original);
            Path output = dir.resolve("outer-mod-retromod.jar");

            invokeRepackage(new FabricModTransformer("26.2"), extracted, output, original);

            Path rebuiltNested = dir.resolve("rebuilt-library.jar");
            try (JarFile outer = new JarFile(output.toFile())) {
                try (var input = outer.getInputStream(
                        outer.getJarEntry("META-INF/jars/library.jar"))) {
                    Files.copy(input, rebuiltNested);
                }
                assertFalse(outer.stream().anyMatch(entry ->
                                entry.getName().equals(SYNTHETIC + ".class")),
                        "a nested helper must not leak into the outer mod namespace");
            }

            String key = "outer-mod.jar!/META-INF/jars/library.jar";
            String relocated = SyntheticEmbedder.embeddedBase(key) + SYNTHETIC;
            try (JarFile jar = new JarFile(rebuiltNested.toFile())) {
                assertEquals("nested-value",
                        jar.getManifest().getMainAttributes().getValue("Nested-Test"),
                        "the nested manifest must survive its rewrite");
                assertNotNull(jar.getJarEntry("nested/"),
                        "nested package directory entries must survive");
                assertNotNull(jar.getJarEntry(relocated + ".class"),
                        "the nested helper must use the outer-and-entry-specific package");

                ClassNode fixture = new ClassNode();
                try (var input = jar.getInputStream(
                        jar.getJarEntry(NESTED_FIXTURE + ".class"))) {
                    new ClassReader(input.readAllBytes()).accept(fixture, 0);
                }
                assertEquals("L" + relocated + ";", fixture.fields.get(0).desc,
                        "the nested class must reference its relocated helper");
            }
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("Fabric packaging fails before writing a jar when helper relocation fails")
    void outerSyntheticCollisionDoesNotPublishDanglingReferences(@TempDir Path dir)
            throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC));

            Path extracted = dir.resolve("extracted");
            writeClass(extracted, FIXTURE, classReferencingSynthetic());
            String generated = SyntheticEmbedder.embeddedBase("collision.jar") + SYNTHETIC;
            writeClass(extracted, generated, emptyClass(generated));
            Path original = dir.resolve("collision.jar");
            writeManifestJar(original);
            Path output = dir.resolve("collision-retromod.jar");

            assertThrows(IOException.class, () -> invokeRepackage(
                    new FabricModTransformer("26.2"), extracted, output, original));
            assertFalse(Files.exists(output),
                    "a failed relocation must not publish a jar with a missing helper");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("a failed nested helper relocation keeps the original JiJ bytes")
    void nestedSyntheticCollisionKeepsOriginalJar(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC));

            Path extracted = dir.resolve("extracted");
            Path nested = extracted.resolve("META-INF/jars/library.jar");
            Files.createDirectories(nested.getParent());
            String key = "outer-mod.jar!/META-INF/jars/library.jar";
            String generated = SyntheticEmbedder.embeddedBase(key) + SYNTHETIC;
            writeNestedFixture(nested, generated);
            byte[] originalNested = Files.readAllBytes(nested);

            Path original = dir.resolve("outer-mod.jar");
            writeManifestJar(original);
            Path output = dir.resolve("outer-mod-retromod.jar");
            invokeRepackage(new FabricModTransformer("26.2"), extracted, output, original);

            try (JarFile outer = new JarFile(output.toFile());
                 var input = outer.getInputStream(
                         outer.getJarEntry("META-INF/jars/library.jar"))) {
                assertArrayEquals(originalNested, input.readAllBytes(),
                        "a nested collision must keep the complete original archive");
            }
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    private static void invokeRepackage(FabricModTransformer transformer, Path source,
                                        Path output, Path original) throws Exception {
        Method method = FabricModTransformer.class.getDeclaredMethod(
                "repackageJar", Path.class, Path.class, Path.class);
        method.setAccessible(true);
        try {
            method.invoke(transformer, source, output, original);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static void writeManifestJar(Path jar) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // The packaging path only needs the source manifest for this fixture.
        }
    }

    private static void writeNestedFixture(Path jar) throws Exception {
        writeNestedFixture(jar, null);
    }

    private static void writeNestedFixture(Path jar, String collisionClass) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Nested-Test", "nested-value");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new java.util.jar.JarEntry("nested/"));
            output.closeEntry();
            output.putNextEntry(new java.util.jar.JarEntry(NESTED_FIXTURE + ".class"));
            output.write(classReferencingSynthetic(NESTED_FIXTURE));
            output.closeEntry();
            if (collisionClass != null) {
                output.putNextEntry(new java.util.jar.JarEntry(collisionClass + ".class"));
                output.write(emptyClass(collisionClass));
                output.closeEntry();
            }
        }
    }

    private static void writeClass(Path root, String name, byte[] bytes) throws Exception {
        Path path = root.resolve(name + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classReferencingSynthetic() {
        return classReferencingSynthetic(FIXTURE);
    }

    private static byte[] classReferencingSynthetic(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "helper", "L" + SYNTHETIC + ";",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
