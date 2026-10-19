/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The translated mod cache serves code as mods, so these cases pin down what it may hand
 * out: only verified entries for the current build and settings, never a stale, removed,
 * tampered, or unindexed file, and never through a link Retromod did not create.
 */
class TranslatedModCacheTest {

    private static final String STAMP_A = "1.4.0-test|hashA|target=inplace/neoforge/26.3/ctx1";
    private static final String STAMP_B = "1.4.0-test|hashB|target=inplace/neoforge/26.3/ctx1";
    private static final Duration NO_WAIT = Duration.ZERO;

    @TempDir
    Path tmp;

    private Path gameDir() throws IOException {
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        return game;
    }

    private TranslatedModCache open(Path game, String stamp) {
        return TranslatedModCache.open(game, "neoforge", "26.3", stamp, NO_WAIT);
    }

    private static Path writeJar(Path file, String marker) throws IOException {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(file);
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("marker.txt"));
            jar.write(marker.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }

    /** Publishes a translation of {@code source} and returns the cached file. */
    private static Path translate(TranslatedModCache cache, SourceJar source, String marker)
            throws IOException {
        Path staged = cache.newStagingFile();
        writeJar(staged, marker);
        CacheEntry entry = cache.publish(source, staged, List.of("examplemod"), "1.0.0");
        return cache.jarsDirectory().resolve(entry.file());
    }

    private static void assertNoStagingLeftovers(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")),
                    "staging files must not stay behind in " + folder);
        }
    }

    @Test
    @DisplayName("a published translation is served under the #47-safe name and survives reopening")
    void publishesAtomicallyAndServesOnTheNextLaunch() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/Cake Cow 1.0.0.jar"), "original");
        byte[] before = Files.readAllBytes(original);
        String sha;
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertEquals(TranslatedModCache.State.READY, cache.state());
            SourceJar source = cache.describe(original, "mods");
            sha = source.sha256();
            assertTrue(cache.shouldTranslate(sha));
            Path cached = translate(cache, source, "translated");
            assertEquals("Cake_Cow_1.0.0-retromod.jar", cached.getFileName().toString());
            assertNoStagingLeftovers(cache.jarsDirectory());
        }
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            Path served = cache.verifiedJar(sha).orElseThrow();
            assertEquals("Cake_Cow_1.0.0-retromod.jar", served.getFileName().toString());
            assertFalse(cache.shouldTranslate(sha), "an OK entry is never translated again");
        }
        assertArrayEquals(before, Files.readAllBytes(original), "the original is never changed");
    }

    @Test
    @DisplayName("the source key is the file's bytes, and a changed file gets a new key")
    void keyFollowsFileContent() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "v1");
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar first = cache.describe(original, "mods");
            assertEquals(TranslatedModCache.sha256(original), first.sha256());
            assertEquals(first.sha256(), cache.describe(original, "mods").sha256());

            writeJar(original, "v2, a different build of the mod");
            Files.setLastModifiedTime(original, FileTime.fromMillis(1_000));
            SourceJar second = cache.describe(original, "mods");
            assertNotEquals(first.sha256(), second.sha256());
            assertEquals(TranslatedModCache.sha256(original), second.sha256());
        }
    }

    @Test
    @DisplayName("a new Retromod build or setting clears every translation and entry")
    void stampChangeWipesTranslationsAndIndex() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "original");
        Path cached;
        String sha;
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar source = cache.describe(original, "mods");
            sha = source.sha256();
            cached = translate(cache, source, "translated");
            cache.recordFailure(cache.describe(
                    writeJar(game.resolve("mods/b.jar"), "broken"), "mods"), "VerifyError");
        }
        try (TranslatedModCache cache = open(game, STAMP_B)) {
            assertEquals(TranslatedModCache.State.READY, cache.state());
            assertTrue(cache.entries().isEmpty(), "entries from the old stamp are dropped");
            assertFalse(Files.exists(cached), "the old build's jar is wiped");
            assertTrue(cache.verifiedJar(sha).isEmpty());
            assertTrue(cache.shouldTranslate(sha));
        }
    }

    @Test
    @DisplayName("changing an output setting such as polyfills_enabled changes the stamp")
    void transformContextIsPartOfTheStamp() throws IOException {
        Path game = gameDir();
        Path config = game.resolve("config/retromod/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"polyfills_enabled\": true}");
        TransformContext on = TransformContext.forGame(game, "neoforge", "21.1.252",
                new Properties());
        Files.writeString(config, "{\"polyfills_enabled\": false}");
        TransformContext off = TransformContext.forGame(game, "neoforge", "21.1.252",
                new Properties());
        assertNotEquals(on.hash(), off.hash());

        Path original = writeJar(game.resolve("mods/a.jar"), "original");
        String sha;
        try (TranslatedModCache cache = TranslatedModCache.open(game, "neoforge", "26.3", on,
                NO_WAIT)) {
            assertEquals(TranslatedModCache.State.READY, cache.state());
            SourceJar source = cache.describe(original, "mods");
            sha = source.sha256();
            translate(cache, source, "with polyfills");
        }
        try (TranslatedModCache cache = TranslatedModCache.open(game, "neoforge", "26.3", off,
                NO_WAIT)) {
            assertTrue(cache.verifiedJar(sha).isEmpty(),
                    "a translation made with polyfills on must not be served with them off");
        }
    }

    @Test
    @DisplayName("an updated file in the same place takes over the familiar name")
    void replacedSourceReusesItsName() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/mod.jar"), "v1");
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar v1 = cache.describe(original, "mods");
            translate(cache, v1, "translated v1");

            writeJar(original, "v2 with more bytes");
            Files.setLastModifiedTime(original, FileTime.fromMillis(5_000));
            SourceJar v2 = cache.describe(original, "mods");
            Path cached = translate(cache, v2, "translated v2");

            assertEquals("mod-retromod.jar", cached.getFileName().toString());
            assertTrue(cache.entry(v1.sha256()).isEmpty(), "the stale entry is gone");
        }
    }

    @Test
    @DisplayName("two different jars with one file name get distinct, hash-suffixed names")
    void collidingNamesGetAHashSuffix() throws IOException {
        Path game = gameDir();
        Path fromMods = writeJar(game.resolve("mods/mod.jar"), "first");
        Path fromInput = writeJar(game.resolve("retromod-input/mod.jar"), "second");
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar a = cache.describe(fromMods, "mods");
            SourceJar b = cache.describe(fromInput, "retromod-input");
            Path first = translate(cache, a, "a");
            Path second = translate(cache, b, "b");

            assertEquals("mod-retromod.jar", first.getFileName().toString());
            assertEquals("mod-retromod-h" + b.sha256().substring(0, 8) + ".jar",
                    second.getFileName().toString());
            assertTrue(Files.exists(first), "the first translation stays in place");
        }
    }

    @Test
    @DisplayName("prune removes entries for removed mods, unindexed jars, and staging leftovers")
    void pruneKeepsOnlyLiveIndexedFiles() throws IOException {
        Path game = gameDir();
        Path kept = writeJar(game.resolve("mods/kept.jar"), "kept");
        Path removed = writeJar(game.resolve("mods/removed.jar"), "removed");
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar keptSource = cache.describe(kept, "mods");
            SourceJar removedSource = cache.describe(removed, "mods");
            Path keptJar = translate(cache, keptSource, "kept");
            Path removedJar = translate(cache, removedSource, "removed");
            Path planted = writeJar(cache.jarsDirectory().resolve("planted-retromod.jar"), "x");
            Path leftover = Files.writeString(cache.jarsDirectory().resolve(".staging-1.tmp"), "");

            TranslatedModCache.PruneReport report = cache.prune(Set.of(keptSource.sha256()));

            assertEquals(1, report.removedEntries());
            assertTrue(Files.exists(keptJar));
            assertFalse(Files.exists(removedJar));
            assertFalse(Files.exists(planted), "a jar no entry names is never kept");
            assertFalse(Files.exists(leftover));
            assertTrue(Files.exists(cache.jarsDirectory().resolve(".cache-stamp")));
            assertTrue(cache.entry(removedSource.sha256()).isEmpty());
        }
    }

    @Test
    @DisplayName("a failed translation is not retried until the stamp or the file changes")
    void failedEntriesStopRetries() throws IOException {
        Path game = gameDir();
        Path broken = writeJar(game.resolve("mods/broken.jar"), "broken");
        Path flaky = writeJar(game.resolve("mods/flaky.jar"), "flaky");
        String brokenSha;
        String flakySha;
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar brokenSource = cache.describe(broken, "mods");
            SourceJar flakySource = cache.describe(flaky, "mods");
            brokenSha = brokenSource.sha256();
            flakySha = flakySource.sha256();
            cache.recordFailure(brokenSource, "VerifyError in\nExampleMixin");
            cache.recordRetry(flakySource, "disk full");
        }
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertFalse(cache.shouldTranslate(brokenSha), "failed stays failed");
            assertEquals("VerifyError in ExampleMixin",
                    cache.entry(brokenSha).orElseThrow().failure());
            assertTrue(cache.shouldTranslate(flakySha), "a transient problem is retried");
        }
        try (TranslatedModCache cache = open(game, STAMP_B)) {
            assertTrue(cache.shouldTranslate(brokenSha), "a new build retries a failure");
        }
    }

    @Test
    @DisplayName("a pending entry counts the launches it waited, so it cannot hide a mod forever")
    void pendingEntriesCountAttempts() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "a");
        for (int launch = 1; launch <= 3; launch++) {
            try (TranslatedModCache cache = open(game, STAMP_A)) {
                SourceJar source = cache.describe(original, "mods");
                assertTrue(cache.shouldTranslate(source.sha256()));
                assertEquals(launch, cache.markPending(source));
            }
        }
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar source = cache.describe(original, "mods");
            translate(cache, source, "done");
            assertEquals(0, cache.entry(source.sha256()).orElseThrow().attempts());
        }
    }

    @Test
    @DisplayName("a started translation is on disk before the transformer runs")
    void startedTranslationIsSavedAtOnce() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "a");
        Path index = game.resolve("retromod-cache/translated/neoforge/26.3/index.json");
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar source = cache.describe(original, "mods");
            assertEquals(1, cache.markPending(source));

            cache.markStarted(source);

            assertTrue(Files.readString(index).contains("\"started\""),
                    "a launch that dies while translating must still leave the record");
            assertEquals(1, cache.entry(source.sha256()).orElseThrow().attempts(),
                    "starting does not count as another launch");
            assertTrue(cache.shouldTranslate(source.sha256()));
        }
    }

    @Test
    @DisplayName("a cached jar changed on disk is dropped instead of served")
    void tamperedTranslationIsNotServed() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "a");
        String sha;
        Path cached;
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar source = cache.describe(original, "mods");
            sha = source.sha256();
            cached = translate(cache, source, "translated");
        }
        writeJar(cached, "something else entirely");
        Files.setLastModifiedTime(cached, FileTime.fromMillis(7_000));
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertTrue(cache.verifiedJar(sha).isEmpty());
            assertTrue(cache.entry(sha).isEmpty());
            assertTrue(cache.shouldTranslate(sha));
        }
    }

    @Test
    @DisplayName("a cached jar rewritten in place with its old size and time is not served")
    void sameSizeRewriteIsNotServed() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "a");
        String sha;
        Path cached;
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar source = cache.describe(original, "mods");
            sha = source.sha256();
            cached = translate(cache, source, "translation A");
        }
        byte[] replacement = Files.readAllBytes(
                writeJar(tmp.resolve("replacement.jar"), "translation B"));
        assertEquals(Files.size(cached), replacement.length,
                "the replacement must keep the size for this case to mean anything");
        FileTime modified = Files.getLastModifiedTime(cached);
        try (FileChannel channel = FileChannel.open(cached, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(replacement), 0);
        }
        Files.setLastModifiedTime(cached, modified);

        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertTrue(cache.verifiedJar(sha).isEmpty(),
                    "only a file that still hashes to the recorded value may load as a mod");
            assertTrue(cache.prepareServeFolder(List.of(sha)).orElseThrow().files().isEmpty());
        }
    }

    @Test
    @DisplayName("a link inside the game directory is refused and nothing is served")
    void linkedCacheFolderIsRefused() throws IOException {
        Path game = gameDir();
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        try {
            Files.createSymbolicLink(game.resolve("retromod-cache"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertEquals(TranslatedModCache.State.UNAVAILABLE, cache.state());
            assertNotNull(cache.unavailableReason());
            assertThrows(IOException.class, cache::newStagingFile);
        }
        try (var files = Files.list(elsewhere)) {
            assertEquals(0, files.count(), "nothing is written through the link");
        }
    }

    @Test
    @DisplayName("a cached entry replaced by a link is not served")
    void linkedCacheEntryIsRefused() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "a");
        String sha;
        Path cached;
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar source = cache.describe(original, "mods");
            sha = source.sha256();
            cached = translate(cache, source, "translated");
        }
        Path outside = writeJar(tmp.resolve("outside.jar"), "planted");
        Files.delete(cached);
        try {
            Files.createSymbolicLink(cached, outside);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertTrue(cache.verifiedJar(sha).isEmpty());
        }
    }

    @Test
    @DisplayName("a game directory reached through a link still gets a working cache")
    void linkedGameDirectoryIsAccepted() throws IOException {
        Path real = gameDir();
        Path linked = tmp.resolve("linked-instance");
        try {
            Files.createSymbolicLink(linked, real);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }
        Path original = writeJar(real.resolve("mods/a.jar"), "a");
        try (TranslatedModCache cache = open(linked, STAMP_A)) {
            assertEquals(TranslatedModCache.State.READY, cache.state(),
                    String.valueOf(cache.unavailableReason()));
            SourceJar source = cache.describe(linked.resolve("mods/a.jar"), "mods");
            assertEquals(linked.resolve("mods/a.jar"), source.path(),
                    "source paths keep the caller's form; loaders compare raw paths");
            Path cached = translate(cache, source, "translated");
            assertTrue(cached.startsWith(real.toRealPath()));
        }
        assertTrue(Files.exists(original));
    }

    @Test
    @DisplayName("a second game sharing the folder gets verified hits read-only")
    void busyLockGivesReadOnlyAccess() throws IOException {
        Path game = gameDir();
        Path original = writeJar(game.resolve("mods/a.jar"), "a");
        String sha;
        try (TranslatedModCache first = open(game, STAMP_A)) {
            SourceJar source = first.describe(original, "mods");
            sha = source.sha256();
            translate(first, source, "translated");
        }
        try (TranslatedModCache holder = open(game, STAMP_A)) {
            assertEquals(TranslatedModCache.State.READY, holder.state());
            try (TranslatedModCache second = open(game, STAMP_A)) {
                assertEquals(TranslatedModCache.State.READ_ONLY, second.state());
                assertTrue(second.verifiedJar(sha).isPresent());
                assertFalse(second.shouldTranslate(sha));
                assertThrows(IOException.class, second::newStagingFile);
            }
            try (TranslatedModCache otherBuild = open(game, STAMP_B)) {
                assertEquals(TranslatedModCache.State.UNAVAILABLE, otherBuild.state(),
                        "a busy cache stamped for another build serves nothing");
            }
        }
    }

    @Test
    @DisplayName("in-game code attaches to the discovery pass without touching the stamp")
    void attachTrustsTheDiscoveryStamp() throws IOException {
        Path game = gameDir();
        try (TranslatedModCache before = TranslatedModCache.attach(game, "neoforge", "26.3",
                NO_WAIT)) {
            assertEquals(TranslatedModCache.State.UNAVAILABLE, before.state(),
                    "nothing to attach to before a discovery pass stamped the folder");
        }
        Path original = writeJar(game.resolve("mods/a.jar"), "a");
        try (TranslatedModCache discovery = open(game, STAMP_A)) {
            discovery.markPending(discovery.describe(original, "mods"));
        }
        String stampBefore = Files.readString(game.resolve(
                "retromod-cache/translated/neoforge/26.3/jars/.cache-stamp"));
        try (TranslatedModCache inGame = TranslatedModCache.attach(game, "neoforge", "26.3",
                NO_WAIT)) {
            assertEquals(TranslatedModCache.State.READY, inGame.state());
            SourceJar source = inGame.describe(original, "mods");
            assertTrue(inGame.shouldTranslate(source.sha256()));
            translate(inGame, source, "translated in game");
        }
        assertEquals(stampBefore, Files.readString(game.resolve(
                "retromod-cache/translated/neoforge/26.3/jars/.cache-stamp")));
        try (TranslatedModCache next = open(game, STAMP_A)) {
            String sha = next.describe(original, "mods").sha256();
            assertTrue(next.verifiedJar(sha).isPresent());
        }
    }

    @Test
    @DisplayName("the serve folder holds exactly the requested verified jars")
    void serveFolderContainsOnlyRequestedVerifiedJars() throws IOException {
        Path game = gameDir();
        Path wanted = writeJar(game.resolve("mods/wanted.jar"), "wanted");
        Path other = writeJar(game.resolve("mods/other.jar"), "other");
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            SourceJar wantedSource = cache.describe(wanted, "mods");
            SourceJar otherSource = cache.describe(other, "mods");
            translate(cache, wantedSource, "wanted");
            translate(cache, otherSource, "other");

            Path serve = game.resolve("retromod-cache/serve/neoforge/26.3");
            writeJar(serve.resolve("stale-from-last-launch.jar"), "stale");

            TranslatedModCache.ServeFolder folder = cache.prepareServeFolder(
                    List.of(wantedSource.sha256(), "0".repeat(64))).orElseThrow();
            try (var files = Files.list(folder.directory())) {
                assertEquals(List.of("wanted-retromod.jar"),
                        files.map(f -> f.getFileName().toString()).sorted().toList());
            }
            Path served = folder.files().get(wantedSource.sha256());
            assertEquals(TranslatedModCache.sha256(
                    cache.verifiedJar(wantedSource.sha256()).orElseThrow()),
                    TranslatedModCache.sha256(served));
        }
    }

    @Test
    @DisplayName("an index that names a path outside the cache is discarded, not followed")
    void unsafeIndexIsDiscarded() throws IOException {
        Path game = gameDir();
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertEquals(TranslatedModCache.State.READY, cache.state());
        }
        Path index = game.resolve("retromod-cache/translated/neoforge/26.3/index.json");
        String sha = "a".repeat(64);
        Files.writeString(index, "{\"format\":1,\"stamp\":\"" + STAMP_A + "\",\"entries\":{\""
                + sha + "\":{\"status\":\"ok\",\"file\":\"../../../../mods/evil.jar\","
                + "\"fileSha256\":\"" + "b".repeat(64) + "\"}}}");
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertEquals(TranslatedModCache.State.READY, cache.state());
            assertTrue(cache.entries().isEmpty());
            assertTrue(cache.verifiedJar(sha).isEmpty());
        }
    }

    @Test
    @DisplayName("loader and version names cannot escape the cache folder")
    void invalidHostNamesAreRefused() throws IOException {
        Path game = gameDir();
        try (TranslatedModCache cache = TranslatedModCache.open(game, "neoforge", "../../mods",
                STAMP_A, NO_WAIT)) {
            assertEquals(TranslatedModCache.State.UNAVAILABLE, cache.state());
        }
        try (TranslatedModCache cache = TranslatedModCache.open(game, "Neo/Forge", "26.3",
                STAMP_A, NO_WAIT)) {
            assertEquals(TranslatedModCache.State.UNAVAILABLE, cache.state());
        }
    }

    @Test
    @DisplayName("the lock lives at the cache root, outside the folder a stamp change wipes")
    void lockAndIndexSurviveAStampWipe() throws IOException {
        Path game = gameDir();
        try (TranslatedModCache cache = open(game, STAMP_A)) {
            assertEquals(TranslatedModCache.State.READY, cache.state());
        }
        Path lock = game.resolve("retromod-cache/.lock");
        Path index = game.resolve("retromod-cache/translated/neoforge/26.3/index.json");
        assertTrue(Files.isRegularFile(lock));
        assertTrue(Files.isRegularFile(index));
        try (TranslatedModCache cache = open(game, STAMP_B)) {
            assertEquals(TranslatedModCache.State.READY, cache.state());
            assertTrue(Files.isRegularFile(lock), "the held lock file is never deleted");
            assertTrue(Files.isRegularFile(index));
        }
        assertTrue(Files.readString(index).contains(STAMP_B));
    }
}
