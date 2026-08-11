/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
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
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AotCompilerSecurityTest {

    @Test
    @DisplayName("archive budgets enforce total bytes and entry count atomically")
    void archiveBudgetEnforcesBothLimits() throws Exception {
        AotCompiler.ArchiveBudget budget = new AotCompiler.ArchiveBudget(10, 2);

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
    @DisplayName("nested archive output refuses growth past its limit")
    void nestedArchiveOutputIsBounded() throws Exception {
        AotCompiler.BoundedArchiveOutput output =
                new AotCompiler.BoundedArchiveOutput(10, 1000);
        byte[] first = new byte[6];
        byte[] rejected = new byte[5];

        output.write(first);
        assertThrows(IOException.class, () -> output.write(rejected));
        assertEquals(6, output.size(), "a rejected write must not consume the budget");

        output.write(new byte[4]);
        assertEquals(10, output.size());
        assertEquals(10, output.toByteArray().length);
    }

    @Test
    @DisplayName("nested archive traversal shares one expanded-byte budget")
    void nestedArchiveTraversalUsesSharedBudget() throws Exception {
        byte[] nestedJar = jarBytes(Map.of(
                "fabric.mod.json", new byte[6],
                "assets/test/data.bin", new byte[6]));
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.2");
        Method transformNested = AotCompiler.class.getDeclaredMethod(
                "transformNestedJarAot", byte[].class, int.class,
                MixinCompatibilityTransformer.class, boolean.class,
                AotCompiler.ArchiveBudget.class);
        transformNested.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> transformNested.invoke(compiler, nestedJar, 1,
                        new MixinCompatibilityTransformer(RetromodTransformer.getInstance()),
                        false, new AotCompiler.ArchiveBudget(10, 10)));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    @DisplayName("a failed AOT rewrite preserves the previous output")
    void failedRewritePreservesPreviousOutput(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("input.jar");
        Path output = directory.resolve("output.jar");
        byte[] previous = "previous output".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);
        writeJar(input, "retromod_aot.properties", "source metadata".getBytes(StandardCharsets.UTF_8));

        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "1.20.1");
        Method compileJar = AotCompiler.class.getDeclaredMethod(
                "compileJar", Path.class, Path.class, ModVersionInfo.class);
        compileJar.setAccessible(true);
        ModVersionInfo mod = new ModVersionInfo(
                "test", "1.0", "1.20.1", "fabric", null,
                Set.of("test/"), Set.of(), false);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> compileJar.invoke(compiler, input, output, mod));
        assertInstanceOf(IOException.class, failure.getCause());
        assertArrayEquals(previous, Files.readAllBytes(output));

        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count(), "failed staged outputs must be removed");
        }
    }

    @Test
    @DisplayName("source hashes match SHA-256 without loading the file at once")
    void sourceHashMatchesSha256(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("source.bin");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AotCompiler.computeHash(file));
    }

    private static void writeJar(Path path, String name, byte[] data) throws Exception {
        Files.write(path, jarBytes(Map.of(name, data)));
    }

    private static byte[] jarBytes(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
