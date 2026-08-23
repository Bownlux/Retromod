/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzyMethodResolverSecurityTest {

    @Test
    @DisplayName("offline MC indexing enforces archive entry and class byte budgets")
    void indexBudgetsAreEnforced(@TempDir Path directory) throws Exception {
        Path manyEntries = directory.resolve("many.jar");
        writeJar(manyEntries,
            new String[]{"first.txt", "second.txt"},
            new byte[][]{{1}, {2}});
        FuzzyMethodResolver entryLimited = new FuzzyMethodResolver();
        assertThrows(IOException.class,
            () -> entryLimited.indexJar(manyEntries, 16, 32, 1));
        assertFalse(entryLimited.isIndexed());

        Path largeClass = directory.resolve("large-class.jar");
        writeJar(largeClass,
            new String[]{"net/minecraft/Large.class"},
            new byte[][]{new byte[9]});
        FuzzyMethodResolver classLimited = new FuzzyMethodResolver();
        assertThrows(IOException.class,
            () -> classLimited.indexJar(largeClass, 8, 32, 10));
        assertFalse(classLimited.isIndexed());

        Path aggregate = directory.resolve("aggregate.jar");
        writeJar(aggregate,
            new String[]{"net/minecraft/First.class", "net/minecraft/Second.class"},
            new byte[][]{new byte[6], new byte[6]});
        FuzzyMethodResolver aggregateLimited = new FuzzyMethodResolver();
        assertThrows(IOException.class,
            () -> aggregateLimited.indexJar(aggregate, 10, 10, 10));
        assertFalse(aggregateLimited.isIndexed());
    }

    @Test
    @DisplayName("a refused MC index cannot leak partial classes into a retry")
    void failedIndexIsClearedBeforeRetry(@TempDir Path directory) throws Exception {
        byte[] staleClass = emptyClass("net/minecraft/Stale");
        Path refused = directory.resolve("refused.jar");
        writeJar(refused,
            new String[]{"net/minecraft/Stale.class", "net/minecraft/Oversized.class"},
            new byte[][]{staleClass, new byte[6]});

        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        assertThrows(IOException.class, () -> resolver.indexJar(
            refused, 1024, staleClass.length + 5L, 10));

        Path accepted = directory.resolve("accepted.jar");
        byte[] currentClass = emptyClass("net/minecraft/Current");
        writeJar(accepted,
            new String[]{"net/minecraft/Current.class"},
            new byte[][]{currentClass});
        resolver.indexJar(accepted, 1024, 1024, 10);

        assertTrue(resolver.hasClass("net/minecraft/Current"));
        assertFalse(resolver.hasClass("net/minecraft/Stale"));
    }

    @Test
    @DisplayName("offline MC indexing refuses normalized duplicate entries")
    void normalizedDuplicateEntriesAreRefused(@TempDir Path directory) throws Exception {
        Path duplicate = directory.resolve("duplicate.jar");
        writeJar(duplicate,
            new String[]{"assets/example//value.txt", "assets/example/value.txt"},
            new byte[][]{{1}, {2}});

        FuzzyMethodResolver resolver = new FuzzyMethodResolver();

        assertThrows(IOException.class,
            () -> resolver.indexJar(duplicate, 1024, 1024, 10));
        assertFalse(resolver.isIndexed());
    }

    @Test
    @DisplayName("offline MC indexing accepts one canonicalized class entry")
    void canonicalizedClassEntryIsIndexed(@TempDir Path directory) throws Exception {
        Path aliased = directory.resolve("aliased.jar");
        writeJar(aliased,
            new String[]{"net//minecraft/Alias.class"},
            new byte[][]{emptyClass("net/minecraft/Alias")});

        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(aliased, 1024, 1024, 10);

        assertTrue(resolver.isIndexed());
        assertTrue(resolver.hasClass("net/minecraft/Alias"));
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null,
            "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJar(Path path, String[] names, byte[][] contents)
            throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (int index = 0; index < names.length; index++) {
                output.putNextEntry(new JarEntry(names[index]));
                output.write(contents[index]);
                output.closeEntry();
            }
        }
    }
}
