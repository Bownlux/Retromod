/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedJarSecurityTest {

    @Test
    @DisplayName("malformed nested archives fail closed")
    void malformedNestedArchiveFailsClosed() throws Exception {
        byte[] malformed = jarOf(Map.of("../Bad.class", new byte[]{1, 2, 3}));

        assertThrows(IOException.class,
                () -> RetromodCli.transformNestedJar(malformed, 1));

        byte[] normalizedDuplicate = jarOf(Map.of(
                "assets/test/value.json", new byte[] {1},
                "assets//test/value.json", new byte[] {2}));
        assertThrows(IOException.class,
                () -> RetromodCli.transformNestedJar(normalizedDuplicate, 1));
    }

    @Test
    @DisplayName("nested sibling transforms share their archive budgets")
    void nestedSiblingsShareBudgets() throws Exception {
        byte[] first = jarOf(Map.of("fabric.mod.json", new byte[6]));
        byte[] second = jarOf(Map.of("fabric.mod.json", new byte[6]));
        RetromodTransformer.NestedArchiveBudget budget =
                new RetromodTransformer.NestedArchiveBudget(12, 3);

        assertNotSame(first,
                RetromodCli.transformNestedJar(first, 1, false, budget));
        assertThrows(IOException.class,
                () -> RetromodCli.transformNestedJar(second, 1, false, budget),
                "the second sibling must not receive a fresh archive budget");
    }

    @Test
    @DisplayName("nested refmap reads share the index and traversal budget")
    void nestedRefmapReadsShareBudget() throws Exception {
        byte[] nested = jarOf(Map.of(
                "test-refmap.json", "{}".getBytes(StandardCharsets.UTF_8)));
        RetromodTransformer.NestedArchiveBudget budget =
                new RetromodTransformer.NestedArchiveBudget(3, 10);

        assertThrows(IOException.class,
                () -> RetromodCli.transformNestedJar(nested, 1, false, budget));
    }

    @Test
    @DisplayName("nested recursion reuses the parent archive budgets")
    void nestedRecursionSharesBudgets() throws Exception {
        byte[] child = jarOf(Map.of("fabric.mod.json", new byte[4]));
        Map<String, byte[]> parentEntries = new LinkedHashMap<>();
        parentEntries.put("fabric.mod.json", new byte[4]);
        parentEntries.put("META-INF/jars/child.jar", child);
        byte[] parent = jarOf(parentEntries);
        RetromodTransformer.NestedArchiveBudget budget =
                new RetromodTransformer.NestedArchiveBudget(Long.MAX_VALUE, 5);

        IOException failure = assertThrows(IOException.class,
                () -> RetromodCli.transformNestedJar(parent, 1, false, budget),
                "the child must not receive fresh limits after the parent consumes the budget");
        assertTrue(failure.getMessage().contains("child.jar"), failure.getMessage());
    }

    @Test
    @DisplayName("empty nested archives respect traversal limits")
    void emptyNestedArchivesRespectTraversalLimits() throws Exception {
        byte[] empty = jarOf(Map.of());
        RetromodTransformer.NestedArchiveBudget budget =
                new RetromodTransformer.NestedArchiveBudget(10, 1);

        assertSame(empty,
                RetromodCli.transformNestedJar(empty, 1, false, budget));
        assertEquals(1, budget.usedEntries());

        assertThrows(IOException.class,
                () -> RetromodCli.transformNestedJar(empty, 1, false, budget),
                "processing must stop when the aggregate entry limit is reached");
        assertEquals(1, budget.usedEntries());
    }

    @Test
    @DisplayName("an unsafe child keeps the previous CLI output")
    void unsafeChildKeepsPreviousOuterOutput(@TempDir Path directory) throws Exception {
        byte[] child = jarOf(Map.of("../Bad.class", new byte[] {1}));
        LinkedHashMap<String, byte[]> outerEntries = new LinkedHashMap<>();
        outerEntries.put("fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));
        outerEntries.put("META-INF/jars/child.jar", child);

        Path input = directory.resolve("input.jar");
        Path output = directory.resolve("output.jar");
        byte[] previous = "previous output".getBytes(StandardCharsets.UTF_8);
        Files.write(input, jarOf(outerEntries));
        Files.write(output, previous);

        Method transform = RetromodCli.class.getDeclaredMethod(
                "transformJar", Path.class, Path.class, RetromodTransformer.class,
                ModVersionInfo.class);
        transform.setAccessible(true);
        ModVersionInfo info = new ModVersionInfo(
                "test", "1.0", "1.20.1", "fabric", null,
                Set.of(), Set.of(), false);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> transform.invoke(null, input, output,
                        RetromodTransformer.getInstance(), info));
        assertInstanceOf(IOException.class, failure.getCause());
        assertArrayEquals(previous, Files.readAllBytes(output));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count(), "failed staged outputs must be removed");
        }
    }

    @Test
    @DisplayName("normalized outer entries cannot replace the previous CLI output")
    void normalizedOuterDuplicateKeepsPreviousOutput(@TempDir Path directory)
            throws Exception {
        LinkedHashMap<String, byte[]> outerEntries = new LinkedHashMap<>();
        outerEntries.put("fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));
        outerEntries.put("META-INF/mods.toml", new byte[] {1});
        outerEntries.put("META-INF//neoforge.mods.toml", new byte[] {2});

        Path input = directory.resolve("input.jar");
        Path output = directory.resolve("output.jar");
        byte[] previous = "previous output".getBytes(StandardCharsets.UTF_8);
        Files.write(input, jarOf(outerEntries));
        Files.write(output, previous);

        Method transform = RetromodCli.class.getDeclaredMethod(
                "transformJar", Path.class, Path.class, RetromodTransformer.class,
                ModVersionInfo.class);
        transform.setAccessible(true);
        ModVersionInfo info = new ModVersionInfo(
                "test", "1.0", "1.20.1", "fabric", null,
                Set.of(), Set.of(), false);

        boolean previousForceNeoForge = McReflect.isForceNeoForge();
        try {
            McReflect.setForceNeoForge(true);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> transform.invoke(null, input, output,
                            RetromodTransformer.getInstance(), info));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("same output path"));
            assertArrayEquals(previous, Files.readAllBytes(output));
        } finally {
            McReflect.setForceNeoForge(previousForceNeoForge);
        }
    }

    @Test
    @DisplayName("rewritten nested output cannot grow beyond its limit")
    void rewrittenNestedOutputIsBounded() throws Exception {
        RetromodCli.BoundedNestedOutput output =
                new RetromodCli.BoundedNestedOutput(10, 1000);

        output.write(new byte[6]);
        assertThrows(IOException.class, () -> output.write(new byte[5]));
        assertEquals(6, output.size(), "a rejected write must not consume the budget");
        output.write(new byte[4]);
        assertEquals(10, output.toByteArray().length);
    }

    @Test
    @DisplayName("rewriting a nested jar preserves its manifest entry")
    void nestedManifestIsPreserved() throws Exception {
        byte[] manifest = "Manifest-Version: 1.0\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", manifest);
        entries.put("fabric.mod.json", "{}".getBytes(StandardCharsets.UTF_8));

        byte[] transformed = RetromodCli.transformNestedJar(jarOf(entries), 1);
        assertArrayEquals(manifest, readEntry(transformed, "META-INF/MANIFEST.MF"));
    }

    private static byte[] readEntry(byte[] jarBytes, String name) throws Exception {
        try (ZipInputStream jar = new ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = jar.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return jar.readAllBytes();
            }
        }
        throw new AssertionError("missing nested entry " + name);
    }

    private static byte[] jarOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
