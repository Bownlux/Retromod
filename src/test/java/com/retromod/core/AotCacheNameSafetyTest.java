/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The background AOT pass names each cached file after a jar entry. An entry name is untrusted,
 * so one that climbs out of the cache directory would have Retromod write a mod's bytes to a path
 * the mod chose.
 */
class AotCacheNameSafetyTest {

    @Test
    @DisplayName("An ordinary class name is cacheable")
    void acceptsNormalClassNames() {
        assertTrue(HybridTransformationEngine.isCacheableClassName("com/example/mod/Thing"));
        assertTrue(HybridTransformationEngine.isCacheableClassName("Thing"));
        assertTrue(HybridTransformationEngine.isCacheableClassName("a/b/C$Inner"));
    }

    @Test
    @DisplayName("A name that climbs out of the cache directory is refused")
    void rejectsTraversal() {
        assertFalse(HybridTransformationEngine.isCacheableClassName("../../../../mods/evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("com/../../evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("/etc/passwd"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("./evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("com//evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName(""));
    }

    @Test
    @DisplayName("A Windows-style or drive-qualified name is refused")
    void rejectsPlatformSpecificEscapes() {
        assertFalse(HybridTransformationEngine.isCacheableClassName("..\\..\\mods\\evil"));
        assertFalse(HybridTransformationEngine.isCacheableClassName("C:/windows/evil"));
    }

    @Test
    @DisplayName("The write itself also refuses a path outside the cache")
    void writeSiteRefusesTraversal() {
        Path cache = Path.of("config/retromod/aot-cache");
        assertThrows(IOException.class,
                () -> ZipSecurity.safeResolve(cache, "../../../../mods/evil.class"),
                "the second guard at the write must reject it too");
        assertDoesNotThrow(() -> ZipSecurity.safeResolve(cache, "com/example/Thing.class"));
    }

    @Test
    @DisplayName("A cached transform is bound to its source class bytes")
    void cachedTransformRequiresMatchingSource() {
        byte[] original = "original-class".getBytes(StandardCharsets.UTF_8);
        byte[] forged = "different-class".getBytes(StandardCharsets.UTF_8);
        byte[] sourceHash = HybridTransformationEngine.hashClassSource(original);

        assertTrue(HybridTransformationEngine.matchesClassSource(sourceHash, original));
        assertFalse(HybridTransformationEngine.matchesClassSource(sourceHash, forged));
    }

    @Test
    @DisplayName("Background completion waits for every mod compilation")
    void completionWaitsForEveryBackgroundTask() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        CompletableFuture<Void> combined = HybridTransformationEngine.afterAllBackgroundTasks(
                List.of(first, second), () -> completed.set(true));
        first.complete(null);
        assertFalse(completed.get());
        assertFalse(combined.isDone());

        second.complete(null);
        assertTrue(completed.get());
        assertTrue(combined.isDone());
    }

    @Test
    @DisplayName("Background input uses one aggregate byte budget across mods")
    void backgroundInputBudgetIsAggregate() throws Exception {
        HybridTransformationEngine.SharedByteBudget budget =
                new HybridTransformationEngine.SharedByteBudget(10);
        budget.reserve(6, "first-mod.class");

        assertThrows(IOException.class, () -> budget.reserve(5, "second-mod.class"));
        assertEquals(6, budget.usedBytes());
    }

    @Test
    @DisplayName("Archive enumeration stops before collecting entries over the limit")
    void archiveEnumerationIsBounded(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("entries.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            for (String name : List.of("one.txt", "two.txt", "three.txt")) {
                output.putNextEntry(new JarEntry(name));
                output.closeEntry();
            }
        }

        try (JarFile jar = new JarFile(archive.toFile())) {
            assertThrows(IOException.class,
                () -> HybridTransformationEngine.collectClassEntryNames(jar, 2));
        }
    }
}
