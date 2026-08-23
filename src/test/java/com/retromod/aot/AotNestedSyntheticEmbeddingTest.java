/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AotNestedSyntheticEmbeddingTest {

    private static final String OLD_HELPER = "legacy/removed/NestedHelper";
    private static final String SYNTHETIC = "com/retromod/generated/AotNestedHelper";
    private static final String USES = "nested/mod/UsesHelper";
    private static final String MANIFEST = "Manifest-Version: 1.0\r\nFixture: retained\r\n\r\n";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void registerSyntheticRedirect() {
        transformer.clearRedirectsForTesting();
        transformer.registerSyntheticClass(SYNTHETIC, simpleClass(SYNTHETIC));
        transformer.registerClassRedirect(OLD_HELPER, SYNTHETIC);
    }

    @AfterEach
    void clearTransformer() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("AOT relocates a referenced synthetic inside the owning recursive nested JAR")
    void embedsSyntheticInRecursiveNestedJar() throws Exception {
        LinkedHashMap<String, byte[]> innerEntries = retainedFixtureEntries();
        innerEntries.put(USES + ".class", classWithField(USES, OLD_HELPER));
        byte[] inner = jar(innerEntries);

        LinkedHashMap<String, byte[]> outerEntries = retainedFixtureEntries();
        outerEntries.put("META-INF/jars/", new byte[0]);
        outerEntries.put("META-INF/jars/inner.jar", inner);
        byte[] outer = jar(outerEntries);

        String outerKey = "outer-fixture.jar";
        byte[] transformedOuter = transformNested(outer, outerKey);
        byte[] transformedInner = entry(transformedOuter, "META-INF/jars/inner.jar");
        String innerKey = outerKey + "!/META-INF/jars/inner.jar";
        String relocated = SyntheticEmbedder.embeddedBase(innerKey) + SYNTHETIC;

        Set<String> outerNames = entryNames(transformedOuter);
        assertTrue(outerNames.contains("META-INF/"));
        assertTrue(outerNames.contains("META-INF/MANIFEST.MF"));
        assertTrue(outerNames.contains("META-INF/jars/"));
        assertEquals(MANIFEST, new String(
                entry(transformedOuter, "META-INF/MANIFEST.MF"), StandardCharsets.UTF_8));

        Set<String> innerNames = entryNames(transformedInner);
        assertTrue(innerNames.contains("META-INF/"));
        assertTrue(innerNames.contains("META-INF/MANIFEST.MF"));
        assertTrue(innerNames.contains("nested/"));
        assertTrue(innerNames.contains("nested/mod/"));
        assertTrue(innerNames.contains(relocated + ".class"));
        assertFalse(innerNames.contains(SYNTHETIC + ".class"),
                "the nested JAR must not publish a shared synthetic copy");
        assertEquals(MANIFEST, new String(
                entry(transformedInner, "META-INF/MANIFEST.MF"), StandardCharsets.UTF_8));

        ClassNode uses = readClass(entry(transformedInner, USES + ".class"));
        assertEquals("L" + relocated + ";", uses.fields.get(0).desc,
                "the nested class must reference its per-JAR synthetic copy");
        assertEquals(relocated,
                new ClassReader(entry(transformedInner, relocated + ".class")).getClassName());
    }

    @Test
    @DisplayName("AOT keeps exact original nested bytes when synthetic relocation fails")
    void failedEmbeddingRestoresOriginalNestedJar() throws Exception {
        String key = "collision-fixture.jar";
        String relocated = SyntheticEmbedder.embeddedBase(key) + SYNTHETIC;
        LinkedHashMap<String, byte[]> entries = retainedFixtureEntries();
        entries.put(USES + ".class", classWithField(USES, OLD_HELPER));
        entries.put(relocated + ".class", simpleClass(relocated));
        byte[] original = jar(entries);

        assertArrayEquals(original, transformNested(original, key),
                "a failed embed must not return a transformed class with a dangling helper");

        LinkedHashMap<String, byte[]> directEntries = retainedFixtureEntries();
        directEntries.put(USES + ".class", classWithField(USES, SYNTHETIC));
        directEntries.put(relocated + ".class", simpleClass(relocated));
        byte[] directInput = jar(directEntries);
        SyntheticEmbedder.ByteEmbeddingResult result =
                SyntheticEmbedder.embedIntoJarBytes(directInput, key, transformer);
        assertFalse(result.succeeded(), "a generated-path collision must be reported as failure");
        assertEquals(0, result.embeddedCount());
        assertSame(directInput, result.jarBytes(),
                "the failed in-memory embed must retain the exact caller bytes");

        byte[] noReferenceInput = jar(retainedFixtureEntries());
        SyntheticEmbedder.ByteEmbeddingResult noReference =
                SyntheticEmbedder.embedIntoJarBytes(noReferenceInput, key, transformer);
        assertTrue(noReference.succeeded(),
                "a valid JAR with no synthetic reference is a successful no-op");
        assertEquals(0, noReference.embeddedCount());
        assertSame(noReferenceInput, noReference.jarBytes());
    }

    private byte[] transformNested(byte[] jarBytes, String syntheticKey) throws Exception {
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method method = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                RetromodTransformer.NestedArchiveBudget.class, String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(compiler, jarBytes, 1,
                new MixinCompatibilityTransformer(transformer), false,
                new RetromodTransformer.NestedArchiveBudget(
                        16 * 1024 * 1024, 10_000), syntheticKey);
    }

    private static LinkedHashMap<String, byte[]> retainedFixtureEntries() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/", new byte[0]);
        entries.put("META-INF/MANIFEST.MF", MANIFEST.getBytes(StandardCharsets.UTF_8));
        entries.put("nested/", new byte[0]);
        entries.put("nested/mod/", new byte[0]);
        return entries;
    }

    private static byte[] classWithField(String name, String fieldType) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "helper",
                "L" + fieldType + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] simpleClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name,
                null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] jar(LinkedHashMap<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                if (!entry.getKey().endsWith("/")) output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Set<String> entryNames(byte[] jarBytes) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }

    private static byte[] entry(byte[] jarBytes, String name) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return input.readAllBytes();
            }
        }
        throw new AssertionError("missing JAR entry " + name);
    }
}
