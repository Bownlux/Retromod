/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullAotSecurityTest {

    @AfterEach
    void resetCompilerSingleton() throws Exception {
        Field instance = FullAotCompiler.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    @DisplayName("class cache names stay in one directory and preserve lookup equivalence")
    void classCacheNamesCannotTraverse() {
        Path cache = Path.of("retromod-cache/full-aot/example").toAbsolutePath().normalize();

        for (String hostile : new String[]{"../../outside", "..\\outside", "/tmp/owned", "C:\\owned"}) {
            String fileName = FullAotCompiler.safeClassCacheFileName(hostile);
            Path resolved = cache.resolve(fileName).normalize();
            assertEquals(cache, resolved.getParent(), hostile + " escaped the mod cache");
            assertFalse(fileName.contains("/"));
            assertFalse(fileName.contains("\\"));
        }

        assertEquals(FullAotCompiler.safeClassCacheFileName("example.mod.Entry"),
                FullAotCompiler.safeClassCacheFileName("example/mod/Entry"),
                "dotted writer names and internal-name lookups must share one cache key");
        assertNotEquals(FullAotCompiler.safeClassCacheFileName("example/a_b/Entry"),
                FullAotCompiler.safeClassCacheFileName("example/a/b_Entry"),
                "flattened names that look alike must retain distinct digests");
    }

    @Test
    @DisplayName("the expanded-byte budget rejects overflow without consuming it")
    void expandedByteBudgetIsAtomic() throws Exception {
        FullAotCompiler.ExpandedByteBudget budget = new FullAotCompiler.ExpandedByteBudget(10);

        budget.reserve(6, "first.class");
        assertThrows(IOException.class, () -> budget.reserve(5, "second.class"));
        assertEquals(6, budget.usedBytes(), "a rejected entry must not consume the remaining budget");
        budget.reserve(4, "third.class");
        assertEquals(10, budget.usedBytes());
    }

    @Test
    @DisplayName("cache readers ignore class files until the completion marker exists")
    void incompleteCacheIsNeverReadable(@TempDir Path gameDir) throws Exception {
        FullAotCompiler compiler = FullAotCompiler.getInstance(gameDir, "26.2");
        Path modCache = Files.createDirectories(
                gameDir.resolve("retromod-cache/full-aot/example"));
        byte[] cachedBytes = new byte[]{1, 2, 3};
        Files.write(modCache.resolve(FullAotCompiler.safeClassCacheFileName("fixture/Entry")),
                cachedBytes);

        assertFalse(compiler.hasCachedCompilation("example"));
        assertNull(compiler.getCachedClass("example", "fixture/Entry"),
                "a class from an interrupted cache write must not be visible");

        Files.writeString(modCache.resolve(".complete"), "complete");
        assertTrue(compiler.hasCachedCompilation("example"));
        assertArrayEquals(cachedBytes,
                compiler.getCachedClass("example", "fixture/Entry"));
    }

    @Test
    @DisplayName("a completed staged cache replaces the old cache as one directory")
    void completedStageReplacesPreviousCache(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectory(dir.resolve("cache"));
        Path target = Files.createDirectory(root.resolve("example"));
        Files.writeString(target.resolve(".complete"), "old");
        Files.writeString(target.resolve("old.class"), "old");

        Path stage = Files.createDirectory(root.resolve("stage"));
        Files.writeString(stage.resolve(".complete"), "new");
        Files.writeString(stage.resolve("new.class"), "new");

        FullAotCompiler.replaceCompletedCache(root, stage, target);

        assertEquals("new", Files.readString(target.resolve(".complete")));
        assertEquals("new", Files.readString(target.resolve("new.class")));
        assertFalse(Files.exists(target.resolve("old.class")));
        assertFalse(Files.exists(stage));
        try (var children = Files.list(root)) {
            assertEquals(List.of(target), children.toList(),
                    "transaction staging and backup directories must be cleaned");
        }
    }

    @Test
    @DisplayName("a failed compilation preserves the previous completed cache")
    void failedCompilationKeepsPreviousCache(@TempDir Path gameDir) throws Exception {
        FullAotCompiler compiler = FullAotCompiler.getInstance(gameDir, "26.2");
        Path cacheRoot = gameDir.resolve("retromod-cache/full-aot");
        Path previous = Files.createDirectory(cacheRoot.resolve("txn"));
        byte[] oldBytes = new byte[]{7, 8, 9};
        Files.write(previous.resolve(FullAotCompiler.safeClassCacheFileName("fixture/Old")),
                oldBytes);
        Files.writeString(previous.resolve(".complete"), "old");

        Path hostileJar = gameDir.resolve("hostile.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(hostileJar))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write("{\"id\":\"txn\",\"version\":\"1.0\"}"
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("../Bad.class"));
            jar.write(new byte[]{0});
            jar.closeEntry();
        }

        compiler.runFullCompilation(List.of(hostileJar)).get();

        assertTrue(compiler.hasCachedCompilation("txn"));
        assertArrayEquals(oldBytes, compiler.getCachedClass("txn", "fixture/Old"),
                "a failed stage must not alter the last completed cache");
        try (var children = Files.list(cacheRoot)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString()
                            .startsWith(".retromod-aot-stage-")),
                    "a failed stage must be removed");
        }
    }
}
