/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NestedJarSecurityTest {

    @Test
    @DisplayName("malformed nested archives are preserved byte for byte")
    void malformedNestedArchiveIsPreserved() throws Exception {
        byte[] malformed = jarOf(Map.of("../Bad.class", new byte[]{1, 2, 3}));

        assertSame(malformed, RetromodCli.transformNestedJar(malformed, 1),
                "a rejected nested archive must return the original byte array");
    }

    @Test
    @DisplayName("nested sibling transforms share their archive budgets")
    void nestedSiblingsShareBudgets() throws Exception {
        byte[] first = jarOf(Map.of("fabric.mod.json", new byte[6]));
        byte[] second = jarOf(Map.of("fabric.mod.json", new byte[6]));
        RetromodTransformer.NestedArchiveBudget traversal =
                new RetromodTransformer.NestedArchiveBudget(10, 10);
        RetromodTransformer.NestedArchiveBudget lookup =
                new RetromodTransformer.NestedArchiveBudget(10, 10);

        assertNotSame(first,
                RetromodCli.transformNestedJar(first, 1, false, traversal, lookup));
        assertSame(second,
                RetromodCli.transformNestedJar(second, 1, false, traversal, lookup),
                "the second sibling must not receive a fresh byte budget");
    }

    @Test
    @DisplayName("nested recursion reuses the parent archive budgets")
    void nestedRecursionSharesBudgets() throws Exception {
        byte[] child = jarOf(Map.of("fabric.mod.json", new byte[4]));
        Map<String, byte[]> parentEntries = new LinkedHashMap<>();
        parentEntries.put("fabric.mod.json", new byte[4]);
        parentEntries.put("META-INF/jars/child.jar", child);
        byte[] parent = jarOf(parentEntries);
        long parentExpandedBytes = 4L + child.length;

        RetromodTransformer.NestedArchiveBudget traversal =
                new RetromodTransformer.NestedArchiveBudget(parentExpandedBytes, 10);
        RetromodTransformer.NestedArchiveBudget lookup =
                new RetromodTransformer.NestedArchiveBudget(parentExpandedBytes, 10);

        assertSame(parent,
                RetromodCli.transformNestedJar(parent, 1, false, traversal, lookup),
                "the child must not receive fresh limits after the parent consumes the budget");
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
