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
import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    @DisplayName("full AOT worker count stays bounded on high-core hosts")
    void workerCountIsBounded() {
        assertEquals(1, FullAotCompiler.workerThreadCount(1));
        assertEquals(2, FullAotCompiler.workerThreadCount(2));
        assertEquals(3, FullAotCompiler.workerThreadCount(4));
        assertEquals(4, FullAotCompiler.workerThreadCount(512));
    }

    @Test
    @DisplayName("an interrupted full AOT wait cancels workers and preserves interrupt status")
    void interruptedWorkerWaitFailsClosed() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        Future<?> worker = executor.submit(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        try {
            Thread.currentThread().interrupt();
            IOException failure = assertThrows(IOException.class,
                    () -> FullAotCompiler.waitForWorkers(List.of(worker)));

            assertTrue(failure.getMessage().contains("interrupted"), failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the worker join must restore the caller's interrupt flag");
            assertTrue(worker.isCancelled(), "the interrupted wait must cancel active work");
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
        }
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("cache readers ignore class files until the completion marker exists")
    void incompleteCacheIsNeverReadable(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
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
        gameDir = gameDir.toRealPath();
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

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> compiler.runFullCompilation(List.of(hostileJar)).get());
        assertInstanceOf(IOException.class, failure.getCause());

        assertTrue(compiler.hasCachedCompilation("txn"));
        assertArrayEquals(oldBytes, compiler.getCachedClass("txn", "fixture/Old"),
                "a failed stage must not alter the last completed cache");
        try (var children = Files.list(cacheRoot)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString()
                            .startsWith(".retromod-aot-stage-")),
                    "a failed stage must be removed");
        }
    }

    @Test
    @DisplayName("only one full AOT compilation can own the running guard")
    void runningGuardIsAtomic(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
        Field runningField = FullAotCompiler.class.getDeclaredField("isRunning");
        assertEquals(AtomicBoolean.class, runningField.getType(),
                "the running guard must remain an atomic compare-and-set");

        FullAotCompiler compiler = FullAotCompiler.getInstance(gameDir, "26.2");
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        List<Path> blockingInput = new AbstractList<>() {
            @Override
            public Path get(int index) {
                throw new IndexOutOfBoundsException(index);
            }

            @Override
            public int size() {
                bodyEntered.countDown();
                try {
                    if (!releaseBody.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release compilation");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("compilation test was interrupted", e);
                }
                return 0;
            }
        };

        CompletableFuture<Integer> active = compiler.runFullCompilation(blockingInput);
        assertTrue(bodyEntered.await(5, TimeUnit.SECONDS));
        assertTrue(compiler.isRunning());

        CompletableFuture<Integer> rejected = compiler.runFullCompilation(List.of());
        assertEquals(0, rejected.get(1, TimeUnit.SECONDS));
        assertFalse(active.isDone(), "the rejected caller must not reset the active run");

        releaseBody.countDown();
        assertEquals(0, active.get(5, TimeUnit.SECONDS));
        assertFalse(compiler.isRunning());
    }

    @Test
    @DisplayName("full AOT refuses a symbolic-link mod input")
    void symlinkedModInputIsRefused(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
        FullAotCompiler compiler = FullAotCompiler.getInstance(gameDir, "26.2");
        Path source = gameDir.resolve("source.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(source))) {
            // The leaf path check must refuse the link before opening this archive.
        }
        Path linkedInput = gameDir.resolve("linked.jar");
        Files.createSymbolicLink(linkedInput, source);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> compiler.runFullCompilation(List.of(linkedInput)).get());

        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("non-symlink"),
                failure.getCause().getMessage());
        assertNoFullAotStages(gameDir);
    }

    @Test
    @DisplayName("full AOT rejects normalized duplicate archive entries")
    void normalizedDuplicateEntriesAreRefused(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
        FullAotCompiler compiler = FullAotCompiler.getInstance(gameDir, "26.2");
        Path archive = gameDir.resolve("duplicates.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry("fixture/Entry.class"));
            jar.write(new byte[] {1});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("fixture//Entry.class"));
            jar.write(new byte[] {2});
            jar.closeEntry();
        }

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> compiler.runFullCompilation(List.of(archive)).get());

        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("duplicate normalized entry"),
                failure.getCause().getMessage());
        assertNoFullAotStages(gameDir);
    }

    @Test
    @DisplayName("full AOT caps every archive at 100000 entries")
    void archiveEntryCountIsBounded(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
        FullAotCompiler compiler = FullAotCompiler.getInstance(gameDir, "26.2");
        Path archive = gameDir.resolve("too-many-entries.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i <= 100_000; i++) {
                jar.putNextEntry(new JarEntry("assets/fixture/" + i));
                jar.closeEntry();
            }
        }

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> compiler.runFullCompilation(List.of(archive)).get());

        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("more than 100000 entries"),
                failure.getCause().getMessage());
        assertNoFullAotStages(gameDir);
    }

    @Test
    @DisplayName("full AOT caches cannot cross target Minecraft versions")
    void fullAotCacheStampIncludesTargetVersion(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
        FullAotCompiler firstTarget = FullAotCompiler.getInstance(gameDir, "26.1");
        Path oldCache = Files.createDirectory(
                gameDir.resolve("retromod-cache/full-aot/fixture"));
        Files.writeString(oldCache.resolve(".complete"), "old");
        assertTrue(firstTarget.hasCachedCompilation("fixture"));

        resetCompilerSingleton();
        FullAotCompiler secondTarget = FullAotCompiler.getInstance(gameDir, "26.2");

        assertFalse(secondTarget.hasCachedCompilation("fixture"));
        assertFalse(Files.exists(oldCache, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.readString(gameDir.resolve("retromod-cache/full-aot/.cache-stamp"))
                .contains("target=26.2"));
    }

    @Test
    @DisplayName("clearing full AOT restores a target stamp reusable by a new instance")
    void fullAotClearLeavesReusableTargetStamp(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
        FullAotCompiler first = FullAotCompiler.getInstance(gameDir, "26.2");
        first.clearCache();

        Path compiled = Files.createDirectory(
            gameDir.resolve("retromod-cache/full-aot/fixture"));
        Files.writeString(compiled.resolve(".complete"), "complete");
        resetCompilerSingleton();

        FullAotCompiler second = FullAotCompiler.getInstance(gameDir, "26.2");
        assertTrue(second.hasCachedCompilation("fixture"));
        assertTrue(Files.readString(gameDir.resolve(
            "retromod-cache/full-aot/.cache-stamp")).contains("target=26.2"));
    }

    @Test
    @DisplayName("a symlinked full AOT cache root disables cache operations")
    void symlinkedFullAotCacheRootFailsClosed(@TempDir Path gameDir) throws Exception {
        gameDir = gameDir.toRealPath();
        Path cacheParent = Files.createDirectories(gameDir.resolve("retromod-cache"));
        Path outside = Files.createDirectory(gameDir.resolve("outside-cache"));
        Path sentinel = outside.resolve("sentinel.txt");
        Files.writeString(sentinel, "keep", StandardCharsets.UTF_8);
        Path cacheLink = cacheParent.resolve("full-aot");
        Files.createSymbolicLink(cacheLink, outside);

        FullAotCompiler compiler = FullAotCompiler.getInstance(gameDir, "26.2");

        assertFalse(compiler.hasCachedCompilation("example"));
        assertNull(compiler.getCachedClass("example", "fixture/Entry"));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> compiler.runFullCompilation(List.of()).get());
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals(0, compiler.getCacheSize());
        compiler.clearCache();
        assertTrue(Files.isSymbolicLink(cacheLink));
        assertEquals("keep", Files.readString(sentinel, StandardCharsets.UTF_8));
    }

    private static void assertNoFullAotStages(Path gameDir) throws Exception {
        Path cacheRoot = gameDir.resolve("retromod-cache/full-aot");
        try (var entries = Files.list(cacheRoot)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".retromod-aot-stage-")));
        }
    }
}
