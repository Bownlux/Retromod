/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that offline nested-JAR transforms keep generated helpers inside their owning JAR. */
class CliNestedSyntheticEmbeddingTest {

    private static final String OLD_HELPER = "legacy/api/Helper";
    private static final String SYNTHETIC = "com/retromod/generated/cli/Helper";
    private static final String FIXTURE = "nested/UsesHelper";
    private static final String ROOT_KEY = "nested-depth-1.jar";

    @Test
    @DisplayName("nested CLI transforms relocate generated helpers and preserve archive resources")
    void relocatesSyntheticAndPreservesArchiveResources() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            registerHelper(transformer);
            byte[] manifest = "Manifest-Version: 1.0\r\nNested-Test: retained\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8);
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("META-INF/MANIFEST.MF", manifest);
            entries.put("nested/", new byte[0]);
            entries.put(FIXTURE + ".class", classReferencing(FIXTURE, OLD_HELPER));
            byte[] original = jarOf(entries);

            byte[] transformed = RetromodCli.transformNestedJar(original, 1);
            String relocated = SyntheticEmbedder.embeddedBase(ROOT_KEY) + SYNTHETIC;

            assertNotSame(original, transformed);
            assertEquals("L" + relocated + ";", fieldDescriptor(
                    requiredEntry(transformed, FIXTURE + ".class")));
            assertNotNull(entry(transformed, relocated + ".class"),
                    "the helper must be copied under the nested JAR's private package");
            assertFalse(hasEntry(transformed, SYNTHETIC + ".class"),
                    "the shared helper name must not leak into the nested JAR");
            assertArrayEquals(manifest, requiredEntry(transformed, "META-INF/MANIFEST.MF"));
            assertTrue(hasEntry(transformed, "nested/"),
                    "explicit package directory entries must survive relocation");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("recursive CLI transforms derive a distinct stable helper key for each nested JAR")
    void recursionUsesPathSpecificSyntheticKeys() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            registerHelper(transformer);
            String childEntry = "META-INF/jarjar/deep.jar";
            byte[] child = jarOf(Map.of(
                    "deep/UsesHelper.class", classReferencing("deep/UsesHelper", OLD_HELPER)));
            Map<String, byte[]> parentEntries = new LinkedHashMap<>();
            parentEntries.put(FIXTURE + ".class", classReferencing(FIXTURE, OLD_HELPER));
            parentEntries.put(childEntry, child);

            byte[] transformed = RetromodCli.transformNestedJar(jarOf(parentEntries), 1);
            byte[] transformedChild = requiredEntry(transformed, childEntry);
            String parentRelocated = SyntheticEmbedder.embeddedBase(ROOT_KEY) + SYNTHETIC;
            String childKey = ROOT_KEY + "!/" + childEntry;
            String childRelocated = SyntheticEmbedder.embeddedBase(childKey) + SYNTHETIC;

            assertEquals("L" + parentRelocated + ";", fieldDescriptor(
                    requiredEntry(transformed, FIXTURE + ".class")));
            assertEquals("L" + childRelocated + ";", fieldDescriptor(
                    requiredEntry(transformedChild, "deep/UsesHelper.class")));
            assertTrue(hasEntry(transformed, parentRelocated + ".class"));
            assertTrue(hasEntry(transformedChild, childRelocated + ".class"));
            assertFalse(hasEntry(transformedChild, parentRelocated + ".class"),
                    "the child must not reuse its parent's generated package");
            assertFalse(hasEntry(transformed, childRelocated + ".class"),
                    "the child's helper must remain inside the child archive");
            assertFalse(hasEntry(transformedChild, SYNTHETIC + ".class"));
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("a failed nested helper embed preserves the complete original JAR")
    void embeddingFailurePreservesOriginalBytes() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            registerHelper(transformer);
            String relocated = SyntheticEmbedder.embeddedBase(ROOT_KEY) + SYNTHETIC;
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(FIXTURE + ".class", classReferencing(FIXTURE, OLD_HELPER));
            entries.put(relocated + ".class", emptyClass(relocated));
            byte[] original = jarOf(entries);

            byte[] transformed = RetromodCli.transformNestedJar(original, 1);

            assertSame(original, transformed,
                    "a collision must not return transformed bytecode with a dangling helper");
            assertEquals("L" + OLD_HELPER + ";", fieldDescriptor(
                    requiredEntry(transformed, FIXTURE + ".class")));
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("a child helper collision keeps that child unchanged without discarding its parent")
    void recursiveEmbeddingFailureIsIsolatedToChild() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            registerHelper(transformer);
            String childEntry = "META-INF/jars/child.jar";
            String childKey = ROOT_KEY + "!/" + childEntry;
            String childRelocated = SyntheticEmbedder.embeddedBase(childKey) + SYNTHETIC;
            Map<String, byte[]> childEntries = new LinkedHashMap<>();
            childEntries.put("child/UsesHelper.class",
                    classReferencing("child/UsesHelper", OLD_HELPER));
            childEntries.put(childRelocated + ".class", emptyClass(childRelocated));
            byte[] originalChild = jarOf(childEntries);
            Map<String, byte[]> parentEntries = new LinkedHashMap<>();
            parentEntries.put(FIXTURE + ".class", classReferencing(FIXTURE, OLD_HELPER));
            parentEntries.put(childEntry, originalChild);
            byte[] originalParent = jarOf(parentEntries);

            byte[] transformed = RetromodCli.transformNestedJar(originalParent, 1);
            String parentRelocated = SyntheticEmbedder.embeddedBase(ROOT_KEY) + SYNTHETIC;

            assertNotSame(originalParent, transformed,
                    "the valid parent transform should survive a rejected child rewrite");
            assertEquals("L" + parentRelocated + ";", fieldDescriptor(
                    requiredEntry(transformed, FIXTURE + ".class")));
            assertArrayEquals(originalChild, requiredEntry(transformed, childEntry),
                    "the rejected child archive must remain byte for byte unchanged");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    private static void registerHelper(RetromodTransformer transformer) {
        transformer.registerClassRedirect(OLD_HELPER, SYNTHETIC);
        transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC));
    }

    private static byte[] classReferencing(String name, String target) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "helper", "L" + target + ";",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String fieldDescriptor(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (FieldNode field : node.fields) {
            if ("helper".equals(field.name)) return field.desc;
        }
        throw new AssertionError("fixture has no helper field");
    }

    private static byte[] jarOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream jar = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new ZipEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static boolean hasEntry(byte[] jarBytes, String name) throws Exception {
        return entry(jarBytes, name) != null;
    }

    private static byte[] requiredEntry(byte[] jarBytes, String name) throws Exception {
        byte[] value = entry(jarBytes, name);
        if (value == null) throw new AssertionError("missing nested entry " + name);
        return value;
    }

    private static byte[] entry(byte[] jarBytes, String name) throws Exception {
        try (ZipInputStream jar = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = jar.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return jar.readAllBytes();
            }
        }
        return null;
    }
}
