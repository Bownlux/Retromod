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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Retromod's Forge and NeoForge entry points either run the rewrite flow from before in-place
 * loading, translate what the discovery pass left pending into the cache, or leave
 * {@code mods/} alone. These cases pin down which one runs, and that the in-place path uses
 * the same transformer as the rewrite flow and writes nowhere but the cache.
 */
class InPlaceLaunchTest {

    @TempDir
    Path tmp;

    private static Properties properties(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) properties.setProperty(pairs[i], pairs[i + 1]);
        return properties;
    }

    @Test
    @DisplayName("the bootstrap's rewrite decision runs the rewrite flow")
    void rewriteModeFromTheBootstrap() {
        InPlaceLaunch launch = InPlaceLaunch.decide(tmp, properties(
                InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_REWRITE));
        assertEquals(InPlaceLaunch.Action.REWRITE, launch.action());
        assertTrue(launch.rewrite());
        assertFalse(launch.isHiddenOriginal(tmp.resolve("mods/a.jar")));
    }

    @Test
    @DisplayName("without the bootstrap, Retromod keeps the rewrite flow it always had")
    void noBootstrapMeansRewrite() {
        assertEquals(InPlaceLaunch.Action.REWRITE,
                InPlaceLaunch.decide(tmp, new Properties()).action());
    }

    @Test
    @DisplayName("write_translated_jars=true wins even when the bootstrap reported nothing")
    void configRewriteWins() {
        InPlaceLaunch launch = InPlaceLaunch.decide(tmp, properties(
                InPlaceLaunch.BOOT_OWNER_PROPERTY, "x",
                InPlaceSettings.SYSTEM_PROPERTY, "true"));
        assertEquals(InPlaceLaunch.Action.REWRITE, launch.action());
    }

    @Test
    @DisplayName("in-place mode without a discovery report leaves mods/ alone")
    void inPlaceWithoutReportLeavesModsAlone() {
        assertEquals(InPlaceLaunch.Action.LEAVE_ALONE, InPlaceLaunch.decide(tmp, properties(
                InPlaceLaunch.BOOT_OWNER_PROPERTY, "x")).action());
        assertEquals(InPlaceLaunch.Action.LEAVE_ALONE, InPlaceLaunch.decide(tmp, properties(
                InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_UNAVAILABLE)).action());
        assertEquals(InPlaceLaunch.Action.LEAVE_ALONE, InPlaceLaunch.decide(tmp, properties(
                InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE,
                InPlaceStatus.STATUS_PROPERTY, tmp.resolve("missing.json").toString()))
                .action());
    }

    private Path game() throws IOException {
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        return game;
    }

    private static DiscoveryEngine.Outcome discover(Path game, String minecraft)
            throws IOException {
        List<Path> listing;
        try (Stream<Path> files = Files.list(game.resolve("mods"))) {
            listing = files.sorted().toList();
        }
        return DiscoveryEngine.run(new DiscoveryEngine.Request(game, game.resolve("mods"),
                listing, "forge", minecraft, "", ForgeModMetadata.FORGE_TOML,
                DiscoveryEngine.ServeStyle.FOLDER, new Properties(), (level, message) -> { }));
    }

    private static InPlaceLaunch launchFor(DiscoveryEngine.Outcome outcome) {
        return InPlaceLaunch.decide(outcome.statusFile().getParent(), properties(
                InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE,
                InPlaceStatus.STATUS_PROPERTY, outcome.statusFile().toString()));
    }

    /** Every file below {@code root} except Retromod's cache, with its bytes. */
    private static Map<String, String> snapshotOutsideCache(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("retromod-cache/")) continue;
                files.put(relative, TranslatedModCache.sha256(file));
            }
        }
        return files;
    }

    /** Entry names and contents; timestamps differ between any two writes of a jar. */
    private static Map<String, String> contents(Path jar) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> all = file.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                try (InputStream input = file.getInputStream(entry)) {
                    entries.put(entry.getName(), java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(input.readAllBytes())));
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return entries;
    }

    @Test
    @DisplayName("the cached translation has the same contents as the rewrite flow's output")
    void cachedTranslationMatchesTheRewriteFlow() throws IOException {
        Path game = game();
        Path source = TestModJars.forgeMod(game.resolve("mods/Parity Mod 1.0.jar"), "parity",
                "1.0.0", "[1.19.2,1.20)");
        Path rewriteOut = Files.createDirectories(tmp.resolve("rewrite-mods"));
        Path rewritten = new ForgeModTransformer("1.20.1").transformMod(source, rewriteOut);
        assertNotNull(rewritten);

        DiscoveryEngine.Outcome first = discover(game, "1.20.1");
        assertEquals(1, launchFor(first).translatePending("1.20.1", () ->
                new ForgeModTransformer("1.20.1")::transformMod));
        DiscoveryEngine.Outcome second = discover(game, "1.20.1");
        Path cached = Files.list(second.serveFolder()).findFirst().orElseThrow();

        assertEquals(rewritten.getFileName(), cached.getFileName(),
                "the same #47-safe file name, so the derived module name matches too");
        assertEquals(contents(rewritten), contents(cached));
    }

    @Test
    @DisplayName("in-place translation writes nothing outside retromod-cache/")
    void translationWritesOnlyIntoTheCache() throws IOException {
        Path game = game();
        TestModJars.forgeMod(game.resolve("mods/old.jar"), "old", "1.0.0", "[1.19.2,1.20)");
        TestModJars.forgeMod(game.resolve("retromod-input/input.jar"), "input", "1.0.0",
                "[1.19.2,1.20)");
        DiscoveryEngine.Outcome first = discover(game, "1.20.1");
        Map<String, String> before = snapshotOutsideCache(game);

        int translated = launchFor(first).translatePending("1.20.1", () ->
                new ForgeModTransformer("1.20.1")::transformMod);

        assertEquals(2, translated);
        assertEquals(before, snapshotOutsideCache(game),
                "mods/, retromod-input/, and the game folder must stay exactly as they were");
        Path jars = game.toRealPath().resolve("retromod-cache/translated/forge/1.20.1/jars");
        try (Stream<Path> files = Files.list(jars)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith(".staging")),
                    "staging folders are removed after each translation");
        }
    }

    @Test
    @DisplayName("nothing is cached when the game reports another version than the loader did")
    void versionMismatchTranslatesNothing() throws IOException {
        Path game = game();
        TestModJars.forgeMod(game.resolve("mods/old.jar"), "old", "1.0.0", "[1.19.2,1.20)");
        DiscoveryEngine.Outcome first = discover(game, "1.20.1");

        int translated = launchFor(first).translatePending("1.21.4",
                () -> (source, out) -> fail("must not translate for the wrong version"));

        assertEquals(0, translated);
        assertEquals(1, InPlaceStatus.read(discover(game, "1.20.1").statusFile())
                .pending().size(), "the source stays pending for a launch that agrees");
    }

    @Test
    @DisplayName("served and held-back originals are recognized for Retromod's own mods/ scans")
    void hiddenOriginalsAreRecognized() throws IOException {
        Path game = game();
        Path oldJar = TestModJars.forgeMod(game.resolve("mods/old.jar"), "old", "1.0.0",
                "[1.19.2,1.20)");
        Path current = TestModJars.forgeMod(game.resolve("mods/current.jar"), "current", "1.0",
                "[1.20.1,1.21)");

        InPlaceLaunch launch = launchFor(discover(game, "1.20.1"));

        assertTrue(launch.isHiddenOriginal(oldJar));
        assertTrue(launch.isHiddenOriginal(game.resolve("./mods/old.jar")));
        assertFalse(launch.isHiddenOriginal(current));
    }

    @Test
    @DisplayName("a jar chosen in the picker is cached now, so the next launch serves it")
    void chosenJarIsServedOnTheNextLaunch() throws IOException {
        Path game = game();
        InPlaceLaunch launch = launchFor(discover(game, "1.20.1"));
        Path chosen = TestModJars.forgeMod(game.resolve("mods/Picked Mod.jar"), "picked",
                "1.0.0", "[1.19.2,1.20)");
        Map<String, String> before = snapshotOutsideCache(game);

        assertEquals(InPlaceLaunch.ChosenOutcome.TRANSLATED, launch.translateChosen(chosen,
                "1.20.1", () -> new ForgeModTransformer("1.20.1")::transformMod));

        assertEquals(before, snapshotOutsideCache(game), "only the cache changes");
        DiscoveryEngine.Outcome next = discover(game, "1.20.1");
        assertTrue(next.hide().contains(chosen), "the original is held back next launch");
        assertNotNull(next.serveFolder(), "its translation is served next launch");
        assertEquals(1, InPlaceStatus.read(next.statusFile()).served().size());
        assertEquals(InPlaceLaunch.ChosenOutcome.ALREADY_TRANSLATED, launchFor(next)
                .translateChosen(chosen, "1.20.1",
                        () -> (source, out) -> fail("an existing copy is not translated again")));
    }

    @Test
    @DisplayName("a chosen jar whose translation fails is recorded and not retried")
    void chosenJarFailureIsRecorded() throws IOException {
        Path game = game();
        InPlaceLaunch launch = launchFor(discover(game, "1.20.1"));
        Path chosen = TestModJars.forgeMod(game.resolve("mods/broken.jar"), "broken", "1.0.0",
                "[1.19.2,1.20)");

        assertEquals(InPlaceLaunch.ChosenOutcome.FAILED,
                launch.translateChosen(chosen, "1.20.1", () -> (source, out) -> null));
        assertEquals(InPlaceLaunch.ChosenOutcome.FAILED, launch.translateChosen(chosen,
                "1.20.1", () -> (source, out) -> fail("a failure is not retried")));
    }

    @Test
    @DisplayName("without an in-place report, a chosen jar waits for the next launch")
    void chosenJarWaitsWithoutAReport() throws IOException {
        Path game = game();
        Path chosen = TestModJars.forgeMod(game.resolve("mods/later.jar"), "later", "1.0.0",
                "[1.19.2,1.20)");
        InPlaceLaunch leaveAlone = InPlaceLaunch.decide(game, properties(
                InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_UNAVAILABLE));

        assertEquals(InPlaceLaunch.ChosenOutcome.NEXT_LAUNCH, leaveAlone.translateChosen(chosen,
                "1.20.1", () -> (source, out) -> fail("nothing is translated")));
        assertFalse(Files.exists(game.resolve("retromod-cache")));
    }

    @Test
    @DisplayName("the launch summary counts served, newly translated, and failed mods")
    void summaryCountsTheLaunch() throws IOException {
        Path game = game();
        TestModJars.forgeMod(game.resolve("mods/good.jar"), "good", "1.0.0", "[1.19.2,1.20)");
        TestModJars.forgeMod(game.resolve("mods/bad.jar"), "bad", "1.0.0", "[1.19.2,1.20)");
        DiscoveryEngine.Outcome first = discover(game, "1.20.1");

        launchFor(first).translatePending("1.20.1", () -> (source, out) ->
                source.getFileName().toString().startsWith("bad")
                        ? null : new ForgeModTransformer("1.20.1").transformMod(source, out));
        assertEquals(new InPlaceLaunch.Summary(0, 1, 1),
                InPlaceLaunch.summaryThisLaunch().orElseThrow());

        launchFor(discover(game, "1.20.1")).translatePending("1.20.1",
                () -> (source, out) -> fail("nothing is pending"));
        assertEquals(new InPlaceLaunch.Summary(1, 0, 1),
                InPlaceLaunch.summaryThisLaunch().orElseThrow(),
                "the next launch serves the good mod and still reports the failed one");
    }
}
