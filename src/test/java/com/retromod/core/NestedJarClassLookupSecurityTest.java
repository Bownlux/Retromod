/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NestedJarClassLookupSecurityTest {

    @Test
    @DisplayName("nested archive budgets enforce bytes and entry count atomically")
    void nestedArchiveBudgetIsAtomic() throws Exception {
        RetromodTransformer.NestedArchiveBudget budget =
                new RetromodTransformer.NestedArchiveBudget(10, 2);

        budget.reserve(6, "first.class");
        assertThrows(IOException.class, () -> budget.reserve(5, "too-large.class"));
        assertEquals(6, budget.usedBytes());
        assertEquals(1, budget.usedEntries());

        budget.reserve(4, "second.class");
        assertThrows(IOException.class, () -> budget.reserve(0, "third.class"));
        assertEquals(10, budget.usedBytes());
        assertEquals(2, budget.usedEntries());
    }

    @Test
    @DisplayName("class lookup charges every expanded nested entry")
    void classLookupChargesClassesAndResources() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("test/Fixture.class", new byte[]{1, 2});
        entries.put("assets/test/data.bin", new byte[]{3, 4, 5});
        byte[] nested = jarOf(entries);

        RetromodTransformer.NestedArchiveBudget exact =
                new RetromodTransformer.NestedArchiveBudget(5, 2);
        Map<String, byte[]> classes = RetromodTransformer.readJarClassBytes(nested, exact);
        assertArrayEquals(new byte[]{1, 2}, classes.get("test/Fixture"));
        assertEquals(5, exact.usedBytes());
        assertEquals(2, exact.usedEntries());

        assertThrows(IOException.class, () -> RetromodTransformer.readJarClassBytes(
                nested, new RetromodTransformer.NestedArchiveBudget(4, 2)));
    }

    @Test
    @DisplayName("class lookup rejects unsafe and duplicate entry names")
    void classLookupRejectsUnsafeAndDuplicateEntries() throws Exception {
        assertThrows(IOException.class, () -> RetromodTransformer.readJarClassBytes(
                jarOf(Map.of("../Bad.class", new byte[]{1}))));

        byte[] duplicate = duplicateEntryJar();
        assertThrows(IOException.class,
                () -> RetromodTransformer.readJarClassBytes(duplicate));
    }

    private static byte[] duplicateEntryJar() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("one/A.class", new byte[]{1});
        entries.put("two/B.class", new byte[]{2});
        byte[] jar = jarOf(entries);
        replaceAll(jar,
                "two/B.class".getBytes(StandardCharsets.UTF_8),
                "one/A.class".getBytes(StandardCharsets.UTF_8));
        return jar;
    }

    private static void replaceAll(byte[] data, byte[] source, byte[] replacement) {
        if (source.length != replacement.length) {
            throw new IllegalArgumentException("replacement must preserve ZIP name length");
        }
        int replacements = 0;
        for (int offset = 0; offset <= data.length - source.length; offset++) {
            boolean match = true;
            for (int index = 0; index < source.length; index++) {
                if (data[offset + index] != source[index]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            System.arraycopy(replacement, 0, data, offset, replacement.length);
            replacements++;
            offset += source.length - 1;
        }
        assertEquals(2, replacements, "the local and central ZIP names must both be patched");
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
