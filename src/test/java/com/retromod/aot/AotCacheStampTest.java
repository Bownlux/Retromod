/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AOT cache must belong to exactly one Retromod build. Users updated Retromod, and the
 * Hybrid engine's unvalidated per-class preload kept serving the PREVIOUS build's cached
 * transforms until the cache was deleted by hand (the old CLAUDE.md pitfall #4). The generation
 * stamp wipes the directory whenever the owning build changes.
 */
class AotCacheStampTest {

    @TempDir
    Path tmp;

    private Path tempRoot() {
        try {
            return tmp.toRealPath();
        } catch (IOException e) {
            throw new AssertionError("temporary directory is unavailable", e);
        }
    }

    private Path cacheDir() {
        return tempRoot().resolve("aot-cache");
    }

    private void seedStaleEntries() throws IOException {
        Files.createDirectories(cacheDir().resolve("com/example"));
        Files.write(cacheDir().resolve("com/example/Old.class"), new byte[]{(byte) 0xCA});
        Files.write(cacheDir().resolve("somemod-aot.jar"), new byte[]{0x50, 0x4B});
    }

    @Test
    @DisplayName("a cache with NO stamp (pre-stamp builds) is wiped and re-stamped")
    void unstampedCacheIsWiped() throws IOException {
        seedStaleEntries();
        AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|abc");
        assertFalse(Files.exists(cacheDir().resolve("com/example/Old.class")),
                "stale per-class entry must be gone (the Hybrid preload trusts it blindly)");
        assertFalse(Files.exists(cacheDir().resolve("somemod-aot.jar")),
                "stale per-jar entry must be gone");
        assertEquals("1.2.0-snapshot.7|abc",
                Files.readString(cacheDir().resolve(".cache-stamp")).trim());
    }

    @Test
    @DisplayName("a cache stamped by a DIFFERENT build is wiped")
    void differentBuildWipes() throws IOException {
        seedStaleEntries();
        Files.writeString(cacheDir().resolve(".cache-stamp"), "1.2.0-snapshot.6|oldhash\n");
        AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|newhash");
        assertFalse(Files.exists(cacheDir().resolve("somemod-aot.jar")));
        assertEquals("1.2.0-snapshot.7|newhash",
                Files.readString(cacheDir().resolve(".cache-stamp")).trim());
    }

    @Test
    @DisplayName("same version but changed self-hash (rebuilt Retromod) also wipes")
    void selfHashChangeWipes() throws IOException {
        seedStaleEntries();
        Files.writeString(cacheDir().resolve(".cache-stamp"), "1.2.0-snapshot.7|hashA\n");
        AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|hashB");
        assertFalse(Files.exists(cacheDir().resolve("somemod-aot.jar")),
                "same AOT_VERSION with different own-classes hash is a different build");
    }

    @Test
    @DisplayName("a cache stamped by the SAME build is kept intact")
    void sameBuildKeepsEntries() throws IOException {
        seedStaleEntries();
        Files.writeString(cacheDir().resolve(".cache-stamp"), "1.2.0-snapshot.7|abc\n");
        AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|abc");
        assertTrue(Files.exists(cacheDir().resolve("somemod-aot.jar")),
                "valid cache entries must survive (that is the point of the cache)");
        assertTrue(Files.exists(cacheDir().resolve("com/example/Old.class")));
    }

    @Test
    @DisplayName("a missing cache directory is created and stamped")
    void missingDirIsCreated() {
        AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|abc");
        assertTrue(Files.isDirectory(cacheDir()));
        assertTrue(Files.exists(cacheDir().resolve(".cache-stamp")));
    }

    @Test
    @DisplayName("the real entry point uses AOT_VERSION + self-hash and never throws")
    void realEntryPointStamps() {
        assertDoesNotThrow(() -> AotCacheStamp.ensureCurrent(cacheDir()));
        assertTrue(Files.exists(cacheDir().resolve(".cache-stamp")));
        assertDoesNotThrow(() -> AotCacheStamp.ensureCurrent(cacheDir()),
                "second call with the same build must be a no-op");
    }

    @Test
    @DisplayName("an oversized stamp is cleared without trusting the stale cache")
    void oversizedStampIsCleared() throws IOException {
        seedStaleEntries();
        Files.write(cacheDir().resolve(".cache-stamp"), new byte[1025]);

        assertTrue(AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|newhash"));
        assertFalse(Files.exists(cacheDir().resolve("somemod-aot.jar")));
        assertEquals("1.2.0-snapshot.7|newhash",
                Files.readString(cacheDir().resolve(".cache-stamp")).trim());
    }

    @Test
    @DisplayName("a symbolic-link cache root is refused without touching its target")
    void symlinkedCacheRootIsRefused() throws IOException {
        Path external = Files.createDirectory(tempRoot().resolve("external"));
        Path sentinel = Files.writeString(external.resolve("sentinel.txt"), "keep");
        try {
            Files.createSymbolicLink(cacheDir(), external);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }

        assertFalse(AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|abc"));
        assertEquals("keep", Files.readString(sentinel));
        assertFalse(Files.exists(external.resolve(".cache-stamp")));
    }

    @Test
    @DisplayName("a symbolic-link parent component is refused without creating a cache")
    void symlinkedParentComponentIsRefused() throws IOException {
        Path external = Files.createDirectory(tempRoot().resolve("external-parent"));
        Path sentinel = Files.writeString(external.resolve("sentinel.txt"), "keep");
        Path linkedParent = tempRoot().resolve("linked-config");
        try {
            Files.createSymbolicLink(linkedParent, external);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }

        Path nestedCache = linkedParent.resolve("aot-cache");
        assertFalse(AotCacheStamp.ensureCurrent(nestedCache, "1.2.0-snapshot.7|abc"));
        assertEquals("keep", Files.readString(sentinel));
        assertFalse(Files.exists(external.resolve("aot-cache")));
    }

    @Test
    @DisplayName("clearing a cache deletes directory links without following them")
    void staleCacheDoesNotFollowDirectoryLinks() throws IOException {
        seedStaleEntries();
        Path external = Files.createDirectory(tempRoot().resolve("external-directory"));
        Path sentinel = Files.writeString(external.resolve("sentinel.txt"), "keep");
        try {
            Files.createSymbolicLink(cacheDir().resolve("linked-directory"), external);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }

        assertTrue(AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|abc"));
        assertEquals("keep", Files.readString(sentinel));
        assertFalse(Files.exists(cacheDir().resolve("linked-directory"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    @DisplayName("a symbolic-link stamp is replaced without changing its target")
    void symlinkedStampIsReplaced() throws IOException {
        seedStaleEntries();
        Path external = Files.writeString(tempRoot().resolve("external-stamp"), "keep\n");
        Path stamp = cacheDir().resolve(".cache-stamp");
        try {
            Files.createSymbolicLink(stamp, external);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }

        assertTrue(AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|abc"));
        assertEquals("keep", Files.readString(external).trim());
        assertFalse(Files.isSymbolicLink(stamp));
        assertFalse(Files.exists(cacheDir().resolve("somemod-aot.jar")));
    }

    @Test
    @DisplayName("stamp staging leaves no predictable or partial files")
    void stampWriteLeavesNoStagingFiles() throws IOException {
        assertTrue(AotCacheStamp.ensureCurrent(cacheDir(), "1.2.0-snapshot.7|abc"));
        try (var entries = Files.list(cacheDir())) {
            assertEquals(List.of(".cache-stamp"), entries
                    .map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    @DisplayName("clearing through AOT restores the stamp for the next compiler")
    void compilerClearLeavesReusableStampedCache() throws IOException {
        Path cache = cacheDir();
        AotCompiler first = new AotCompiler(new ShimRegistry(), "26.1", cache);
        Files.writeString(cache.resolve("old-aot.jar"), "old");

        first.clearCache();
        assertFalse(Files.exists(cache.resolve("old-aot.jar")));
        assertTrue(Files.isRegularFile(cache.resolve(".cache-stamp")));
        Path compiled = Files.writeString(cache.resolve("new-aot.jar"), "new");

        new AotCompiler(new ShimRegistry(), "26.1", cache);
        assertEquals("new", Files.readString(compiled));
    }
}
