/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.ForgeModTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI's view of a game's translated mod cache: what it lists, what export copies out for
 * sharing, and what clear removes. Every case builds the cache the way a launch does, through
 * the discovery pass and the entry point's translation.
 */
class CacheToolsTest {

    @TempDir
    Path tmp;

    private Path game() throws IOException {
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        return game;
    }

    /** One launch: discovery, then translation of whatever it left pending. */
    private static void launch(Path game, InPlaceLaunch.Translator translator)
            throws IOException {
        List<Path> listing;
        try (Stream<Path> files = Files.list(game.resolve("mods"))) {
            listing = files.sorted().toList();
        }
        DiscoveryEngine.Outcome outcome = DiscoveryEngine.run(new DiscoveryEngine.Request(game,
                game.resolve("mods"), listing, "forge", "1.20.1", "", ForgeModMetadata.FORGE_TOML,
                DiscoveryEngine.ServeStyle.FOLDER, new Properties(), (level, message) -> { }));
        Properties properties = new Properties();
        properties.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        properties.setProperty(InPlaceStatus.STATUS_PROPERTY, outcome.statusFile().toString());
        InPlaceLaunch.decide(game, properties).translatePending("1.20.1", () -> translator);
    }

    private static InPlaceLaunch.Translator realTranslatorExceptFor(String failingPrefix) {
        return (source, out) -> source.getFileName().toString().startsWith(failingPrefix)
                ? null : new ForgeModTransformer("1.20.1").transformMod(source, out);
    }

    /** A game with one translated mod and one whose translation failed. */
    private Path gameWithTranslations() throws IOException {
        Path game = game();
        TestModJars.forgeMod(game.resolve("mods/Good Mod 1.0.jar"), "goodmod", "1.0.0",
                "[1.19.2,1.20)");
        TestModJars.forgeMod(game.resolve("mods/broken.jar"), "broken", "1.0.0",
                "[1.19.2,1.20)");
        launch(game, realTranslatorExceptFor("broken"));
        return game;
    }

    private static final CacheTools.Host FORGE_1201 = new CacheTools.Host("forge", "1.20.1");

    @Test
    @DisplayName("export copies translated mods and lists the ones it left out")
    void exportCopiesOnlyTranslatedMods() throws IOException {
        Path game = gameWithTranslations();
        Path output = tmp.resolve("shared");

        CacheTools.ExportResult result = CacheTools.export(game, FORGE_1201, output, List.of());

        assertEquals(List.of(output.resolve("Good_Mod_1.0-retromod.jar")), result.exported(),
                "the exported file keeps the cache's #47-safe name");
        Path cached = game.toRealPath().resolve(
                "retromod-cache/translated/forge/1.20.1/jars/Good_Mod_1.0-retromod.jar");
        assertEquals(TranslatedModCache.sha256(cached),
                TranslatedModCache.sha256(result.exported().get(0)),
                "the shared jar is exactly what this game loads");
        assertEquals(1, result.skipped().size());
        assertEquals("broken.jar", result.skipped().get(0).sourceName());
        assertTrue(result.skipped().get(0).reason().startsWith("translation failed"));
        try (Stream<Path> files = Files.list(output)) {
            assertEquals(1, files.count(), "no temporary files are left behind");
        }
        if (output.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Path ordinary = Files.writeString(tmp.resolve("ordinary.txt"), "x");
            assertEquals(Files.getPosixFilePermissions(ordinary),
                    Files.getPosixFilePermissions(result.exported().get(0)),
                    "a jar made for sharing gets normal permissions, not owner-only ones");
        }
    }

    @Test
    @DisplayName("export defaults to retromod-output/mods and can pick one mod by id or file")
    void exportSelectsByModIdOrFileName() throws IOException {
        Path game = gameWithTranslations();
        Path output = game.resolve(CacheTools.EXPORT_FOLDER);

        CacheTools.ExportResult byId = CacheTools.export(game, FORGE_1201, output,
                List.of("goodmod", "missing"));
        assertEquals(1, byId.exported().size());
        assertTrue(byId.skipped().isEmpty(), "the failed mod was not asked for");
        assertEquals(List.of("missing"), byId.unmatched());

        CacheTools.ExportResult byFile = CacheTools.export(game, FORGE_1201, output,
                List.of("good mod 1.0.jar"));
        assertEquals(1, byFile.exported().size(), "file names match without case");
    }

    @Test
    @DisplayName("a cached jar changed on disk is never exported")
    void exportRefusesAChangedCacheFile() throws IOException {
        Path game = gameWithTranslations();
        Path cached = game.toRealPath().resolve(
                "retromod-cache/translated/forge/1.20.1/jars/Good_Mod_1.0-retromod.jar");
        Files.writeString(cached, "not the translation");

        CacheTools.ExportResult result = CacheTools.export(game, FORGE_1201,
                tmp.resolve("shared"), List.of("goodmod"));

        assertTrue(result.exported().isEmpty());
        assertTrue(result.skipped().get(0).reason().contains("changed on disk"));
        assertFalse(Files.exists(tmp.resolve("shared/Good_Mod_1.0-retromod.jar")));
    }

    @Test
    @DisplayName("a cache from another Retromod build is not exported")
    void exportRefusesAStaleCache() throws IOException {
        Path game = gameWithTranslations();
        Files.writeString(game.toRealPath().resolve(
                "retromod-cache/translated/forge/1.20.1/jars/.cache-stamp"), "other-build\n");

        IOException failure = assertThrows(IOException.class, () -> CacheTools.export(game,
                FORGE_1201, tmp.resolve("shared"), List.of()));
        assertTrue(failure.getMessage().contains("Launch the game once"));
        assertFalse(CacheTools.report(game, FORGE_1201).consistent());
    }

    @Test
    @DisplayName("export refuses a folder inside the cache, which Retromod cleans")
    void exportRefusesAFolderInsideTheCache() throws IOException {
        Path game = gameWithTranslations();
        assertThrows(IOException.class, () -> CacheTools.export(game, FORGE_1201,
                game.resolve("retromod-cache/serve/out"), List.of()));
    }

    @Test
    @DisplayName("export and status read the cache without changing it")
    void readingLeavesTheCacheAlone() throws IOException {
        Path game = gameWithTranslations();
        Path index = game.toRealPath().resolve("retromod-cache/translated/forge/1.20.1/index.json");
        String before = Files.readString(index);

        CacheTools.Report report = CacheTools.report(game, FORGE_1201);
        CacheTools.export(game, FORGE_1201, tmp.resolve("shared"), List.of());

        assertTrue(report.consistent());
        assertEquals(1, report.count(CacheEntry.Status.OK));
        assertEquals(1, report.count(CacheEntry.Status.FAILED));
        assertEquals(before, Files.readString(index));
    }

    @Test
    @DisplayName("the host cache is chosen automatically only when there is one")
    void chooseHostNeedsAnAnswerWhenThereAreSeveral() throws IOException {
        Path game = game();
        IOException none = assertThrows(IOException.class,
                () -> CacheTools.chooseHost(game, null, null));
        assertTrue(none.getMessage().contains("Launch the game once"));

        TestModJars.forgeMod(game.resolve("mods/old.jar"), "old", "1.0.0", "[1.19.2,1.20)");
        launch(game, realTranslatorExceptFor("none"));
        assertEquals(FORGE_1201, CacheTools.chooseHost(game, null, null));
        Files.createDirectories(game.resolve("retromod-cache/translated/neoforge/26.3"));

        IOException several = assertThrows(IOException.class,
                () -> CacheTools.chooseHost(game, null, null));
        assertTrue(several.getMessage().contains("--target"), several.getMessage());
        assertEquals(FORGE_1201, CacheTools.chooseHost(game, "forge", null));
        assertEquals(new CacheTools.Host("neoforge", "26.3"),
                CacheTools.chooseHost(game, null, "26.3"));
    }

    @Test
    @DisplayName("clear removes translations and serve folders, and keeps the runtime")
    void clearRemovesOnlyTranslations() throws IOException {
        Path game = gameWithTranslations();
        Path cacheRoot = game.toRealPath().resolve("retromod-cache");
        Path runtime = Files.createDirectories(cacheRoot.resolve("runtime/forge-1.20.1-x"));
        Files.writeString(runtime.resolve("retromod.jar"), "runtime");

        CacheTools.ClearResult result = CacheTools.clear(game);

        assertFalse(result.busy());
        assertFalse(Files.exists(cacheRoot.resolve("translated")));
        assertFalse(Files.exists(cacheRoot.resolve("serve")));
        assertTrue(Files.exists(runtime.resolve("retromod.jar")));
        assertTrue(Files.exists(game.resolve("mods/broken.jar")), "mods/ is never touched");
    }

    @Test
    @DisplayName("clear refuses while a game holds the cache lock")
    void clearWaitsForTheGame() throws IOException {
        Path game = gameWithTranslations();
        Path lockFile = game.toRealPath().resolve("retromod-cache/.lock");
        try (CacheLock held = CacheLock.acquire(lockFile, Duration.ZERO).orElseThrow()) {
            assertTrue(CacheTools.clear(game).busy());
        }
        assertTrue(Files.exists(game.toRealPath().resolve("retromod-cache/translated")));
    }

    @Test
    @DisplayName("rewrite-mode jars in mods/ are listed with their originals from backups only")
    void rewriteOutputsFindTheirOriginals() throws IOException {
        Path game = game();
        Path processed = Files.createDirectories(game.resolve("retromod-input/processed"));
        TestModJars.forgeMod(processed.resolve("old.jar"), "old", "1.0.0", "[1.19.2,1.20)");
        TestModJars.jar(game.resolve("mods/old-retromod.jar"),
                stamped("old.jar"), Map.of("META-INF/mods.toml", "x"));
        TestModJars.jar(game.resolve("mods/odd-retromod.jar"),
                stamped("../retromod-input/processed/old.jar"), Map.of());
        TestModJars.forgeMod(game.resolve("mods/native.jar"), "native", "1.0", "[1.20.1,)");

        List<CacheTools.RewriteOutput> outputs = CacheTools.rewriteOutputs(game);

        assertEquals(2, outputs.size(), "the native mod is not Retromod output");
        CacheTools.RewriteOutput odd = outputs.get(0);
        CacheTools.RewriteOutput old = outputs.get(1);
        assertEquals("odd-retromod.jar", odd.jar().getFileName().toString());
        assertEquals(Optional.empty(), odd.original(),
                "a manifest that names a path is not followed");
        assertEquals(processed.toRealPath().resolve("old.jar"), old.original().orElseThrow());
    }

    private static Manifest stamped(String originalName) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(TranslatedJarMarkers.TRANSFORMED, "true");
        manifest.getMainAttributes().putValue(CacheTools.ORIGINAL_JAR, originalName);
        return manifest;
    }
}
