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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The discovery pass decides, before any mod code runs, which originals the loader sees and
 * which cached translations it loads instead. These cases pin down the rules a player relies
 * on: the original is never changed, a translation is served only after it exists, a jar the
 * loader would load anyway is not hidden, and nothing is hidden or retried forever.
 */
class DiscoveryEngineTest {

    private static final String FORGE_HOST = "1.20.1";
    private static final String NEO_HOST = "26.3";

    @TempDir
    Path tmp;

    private final List<String> log = new ArrayList<>();

    private Path game() throws IOException {
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        return game;
    }

    private DiscoveryEngine.Outcome forge(Path game) throws IOException {
        return forge(game, new Properties());
    }

    private DiscoveryEngine.Outcome forge(Path game, Properties properties) throws IOException {
        return discover(game, "forge", FORGE_HOST, ForgeModMetadata.FORGE_TOML,
                DiscoveryEngine.ServeStyle.FOLDER, properties);
    }

    private DiscoveryEngine.Outcome neo(Path game) throws IOException {
        return discover(game, "neoforge", NEO_HOST, ForgeModMetadata.NEOFORGE_TOML,
                DiscoveryEngine.ServeStyle.FILES, new Properties());
    }

    private DiscoveryEngine.Outcome discover(Path game, String loader, String minecraft,
            String hostToml, DiscoveryEngine.ServeStyle style, Properties properties)
            throws IOException {
        return discover(game, loader, minecraft, "test-build", hostToml, style, properties);
    }

    private DiscoveryEngine.Outcome discover(Path game, String loader, String minecraft,
            String loaderBuild, String hostToml, DiscoveryEngine.ServeStyle style,
            Properties properties) throws IOException {
        List<Path> listing;
        try (Stream<Path> files = Files.list(game.resolve("mods"))) {
            listing = files.sorted().toList();
        }
        return DiscoveryEngine.run(new DiscoveryEngine.Request(game, game.resolve("mods"),
                listing, loader, minecraft, loaderBuild, hostToml, style, properties,
                (level, message) -> log.add(level + ": " + message)));
    }

    private static InPlaceStatus status(DiscoveryEngine.Outcome outcome) throws IOException {
        assertNotNull(outcome.statusFile(), "an in-place pass must write a status file");
        return InPlaceStatus.read(outcome.statusFile());
    }

    /** Runs the entry point's translation step with {@code translator} for this pass. */
    private static int translate(DiscoveryEngine.Outcome outcome,
            InPlaceLaunch.Translator translator) {
        Properties properties = new Properties();
        properties.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        properties.setProperty(InPlaceStatus.STATUS_PROPERTY, outcome.statusFile().toString());
        InPlaceLaunch launch = InPlaceLaunch.decide(outcome.statusFile().getParent(), properties);
        assertEquals(InPlaceLaunch.Action.TRANSLATE_INTO_CACHE, launch.action());
        return launch.translatePending(launch.status().orElseThrow().minecraft(),
                () -> translator);
    }

    /** A stand-in translator that copies the source with a marker, like a real translation. */
    private static InPlaceLaunch.Translator copyTranslator(String marker) {
        return (source, outputDir) -> {
            String base = TranslatedModCache.safeBaseName(source.getFileName().toString());
            Path out = outputDir.resolve(base + "-retromod.jar");
            String toml;
            try (var jar = new java.util.jar.JarFile(source.toFile())) {
                var entry = jar.getJarEntry("META-INF/mods.toml");
                toml = new String(jar.getInputStream(entry).readAllBytes(),
                        StandardCharsets.UTF_8);
            }
            return TestModJars.jar(out, null, Map.of("META-INF/mods.toml",
                    toml.replaceAll("versionRange=\"\\[1\\.19[^\"]*\"", "versionRange=\"[1.19,)\""),
                    "translated.txt", marker));
        };
    }

    private static byte[] bytes(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    private static Path old(Path game, String name) throws IOException {
        return TestModJars.forgeMod(game.resolve("mods").resolve(name), "oldmod", "1.0.0",
                "[1.19.2,1.20)");
    }

    @Test
    @DisplayName("an old mod the loader would refuse is held back and queued, and mods/ is unchanged")
    void holdsBackARefusedOldModAndQueuesIt() throws IOException {
        Path game = game();
        Path oldJar = old(game, "Old Mod 1.0.jar");
        Path current = TestModJars.forgeMod(game.resolve("mods/current.jar"), "current", "2.0",
                "[1.20.1,1.21)");
        Path fabric = TestModJars.fabricMod(game.resolve("mods/fabric.jar"), "fab", "1.19.2");
        byte[] before = bytes(oldJar);

        DiscoveryEngine.Outcome outcome = forge(game);

        assertEquals(InPlaceStatus.MODE_IN_PLACE, outcome.mode());
        assertEquals(List.of(oldJar), outcome.hide(), "only the refused old mod is held back");
        InPlaceStatus status = status(outcome);
        assertEquals(1, status.pending().size());
        assertTrue(status.pending().get(0).hidden());
        assertTrue(status.isHidden(oldJar));
        assertFalse(status.isHidden(current));
        assertFalse(status.isHidden(fabric));
        assertArrayEquals(before, bytes(oldJar), "the original must never change");
        assertNull(outcome.serveFolder(), "nothing is translated yet, so nothing is served");
    }

    @Test
    @DisplayName("an old mod the loader would still load stays visible until it is translated")
    void leavesAnAcceptedOldModVisible() throws IOException {
        Path game = game();
        Path accepted = TestModJars.forgeMod(game.resolve("mods/open-range.jar"), "openrange",
                "1.0", "[1.19,)");

        DiscoveryEngine.Outcome outcome = forge(game);

        assertEquals(List.of(), outcome.hide(),
                "holding back a jar the loader accepts could break a mod that depends on it");
        InPlaceStatus status = status(outcome);
        assertEquals(1, status.pending().size());
        assertFalse(status.pending().get(0).hidden());
        assertFalse(status.isHidden(accepted));
    }

    @Test
    @DisplayName("an old mod the loader stopped on is held back on the next launch and translated")
    void heldBackAfterTheLoaderStoppedOnIt() throws IOException {
        Path game = game();
        Path predicted = TestModJars.forgeMod(game.resolve("mods/predicted.jar"), "predicted",
                "1.0", "[1.19,)");

        assertEquals(List.of(), forge(game).hide(),
                "the first launch expects the loader to load it");
        // Nothing is translated: the loader refused the jar before Retromod's entry point ran.
        DiscoveryEngine.Outcome second = forge(game);

        assertEquals(List.of(predicted), second.hide(),
                "a jar still waiting from an earlier launch proves the entry point never ran");
        assertEquals(1, translate(second, copyTranslator("after-refusal")));
        assertEquals(1, status(forge(game)).served().size());
    }

    @Test
    @DisplayName("a loaderVersion or required range the host fails is held back at once")
    void boundedLoaderAndDependencyRangesAreHeldBack() throws IOException {
        Path game = game();
        Path boundLoader = modWithToml(game.resolve("mods/rmbound.jar"),
                "modLoader=\"javafml\"\nloaderVersion=\"[43,44)\"\nlicense=\"MIT\"\n"
                        + "[[mods]]\nmodId=\"rmbound\"\nversion=\"1.0.0\"\n"
                        + minecraftDependency("rmbound", "[1.19.2,)"));
        Path boundLibrary = modWithToml(game.resolve("mods/rmdep.jar"),
                "modLoader=\"javafml\"\nloaderVersion=\"[43,)\"\nlicense=\"MIT\"\n"
                        + "[[mods]]\nmodId=\"rmdep\"\nversion=\"1.0.0\"\n"
                        + minecraftDependency("rmdep", "[1.19.2,)")
                        + "[[dependencies.rmdep]]\nmodId=\"jei\"\nmandatory=true\n"
                        + "versionRange=\"[11.0,12.0)\"\n");
        Path open = modWithToml(game.resolve("mods/rmopen.jar"),
                "modLoader=\"javafml\"\nloaderVersion=\"[43,)\"\nlicense=\"MIT\"\n"
                        + "[[mods]]\nmodId=\"rmopen\"\nversion=\"1.0.0\"\n"
                        + minecraftDependency("rmopen", "[1.19.2,)"));

        DiscoveryEngine.Outcome outcome = discover(game, "forge", "1.21.4", "54.1.22",
                ForgeModMetadata.FORGE_TOML, DiscoveryEngine.ServeStyle.FOLDER, new Properties());

        assertEquals(List.of(boundLoader, boundLibrary), outcome.hide(),
                "Forge refuses both at discovery, before Retromod could translate them");
        assertFalse(status(outcome).isHidden(open));
    }

    private static Path modWithToml(Path file, String toml) throws IOException {
        return TestModJars.jar(file, null, Map.of("META-INF/mods.toml", toml));
    }

    private static String minecraftDependency(String modId, String range) {
        return "[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"" + range + "\"\n";
    }

    @Test
    @DisplayName("after the entry point translates it, the next launch serves only the verified copy")
    void servesTheCachedCopyOnTheNextLaunch() throws IOException {
        Path game = game();
        Path oldJar = old(game, "oldmod.jar");
        byte[] before = bytes(oldJar);

        assertEquals(1, translate(forge(game), copyTranslator("v1")));
        DiscoveryEngine.Outcome second = forge(game);

        assertEquals(List.of(oldJar), second.hide());
        assertNotNull(second.serveFolder());
        try (Stream<Path> served = Files.list(second.serveFolder())) {
            assertEquals(List.of("oldmod-retromod.jar"),
                    served.map(p -> p.getFileName().toString()).toList(),
                    "the serve folder holds exactly the verified copy");
        }
        InPlaceStatus status = status(second);
        assertEquals(1, status.served().size());
        assertEquals(List.of(), status.pending());
        assertArrayEquals(before, bytes(oldJar));
    }

    @Test
    @DisplayName("NeoForge 20.5+ takes single files from the cache and skips Forge-only jars")
    void neoForgeServesFilesAndHoldsBackForgeOnlyJars() throws IOException {
        Path game = game();
        Path forgeOnly = TestModJars.forgeMod(game.resolve("mods/cake.jar"), "cake", "1.0",
                "[1.20.1]");
        TestModJars.fabricMod(game.resolve("mods/fabric-old.jar"), "fabold", "1.20.1");

        DiscoveryEngine.Outcome first = neo(game);
        assertEquals(List.of(forgeOnly), first.hide(),
                "NeoForge skips a mods.toml-only jar anyway, and Fabric jars belong to Connector");
        assertEquals(1, translate(first, copyTranslator("neo")));

        DiscoveryEngine.Outcome second = neo(game);
        assertEquals(1, second.serveFiles().size());
        Path served = second.serveFiles().get(0);
        assertTrue(served.startsWith(game.toRealPath().resolve("retromod-cache")),
                "served copies live in the cache: " + served);
        assertNull(second.serveFolder());
    }

    @Test
    @DisplayName("a Forge jar with no minecraft dependency is translated when NeoForge would skip it")
    void versionlessForgeJarIsTranslatedWhenRefused() throws IOException {
        // Macaw's Forge 1.21.1 jars: a javafml mods.toml with no minecraft dependency at all.
        Path game = game();
        Path versionless = modWithToml(game.resolve("mods/mcw-bridges.jar"),
                "modLoader=\"javafml\"\nloaderVersion=\"[28,)\"\nlicense=\"x\"\n"
                        + "[[mods]]\nmodId=\"mcwbridges\"\nversion=\"3.1.2\"\n");

        DiscoveryEngine.Outcome outcome = neo(game);

        assertEquals(List.of(versionless), outcome.hide(),
                "NeoForge skips a Forge-only jar, so leaving it visible would leave it unloaded");
        assertEquals(1, status(outcome).pending().size());
    }

    @Test
    @DisplayName("a Forge jar whose Minecraft version is only in its manifest is translated on newer Forge")
    void manifestVersionedForgeJarIsTranslated() throws IOException {
        // Project Rankine (#358): the Forge MDK template header, no minecraft dependency, a
        // forge range left at the 1.16.5 floor that every newer Forge accepts, and the real
        // version only in Implementation-Version. Forge would load it, so only its version
        // can mark it old.
        String toml = "modLoader=\"javafml\" #mandatory\nloaderVersion=\"[36,)\" #mandatory\n"
                + "license=\"MIT\"\n[[mods]] #mandatory\nmodId=\"rankine\" #mandatory\n"
                + "version=\"1.3.8-hf2\" #mandatory\n[[dependencies.rankine]]\n"
                + "    modId=\"forge\"\n    mandatory=true\n    versionRange=\"[36.0.1,)\"\n";
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION,
                "1.18.2-1.3.8-hf2");
        Map<String, String> forgeHosts = Map.of("1.21.1", "52.1.16", "26.3", "66.0.12");

        for (Map.Entry<String, String> host : forgeHosts.entrySet()) {
            Path game = tmp.resolve("game-" + host.getKey());
            Files.createDirectories(game.resolve("mods"));
            Path rankine = TestModJars.jar(game.resolve("mods/rankine-1.18.2-1.3.8-hf2.jar"),
                    manifest, Map.of("META-INF/mods.toml", toml));

            DiscoveryEngine.Outcome outcome = discover(game, "forge", host.getKey(),
                    host.getValue(), ForgeModMetadata.FORGE_TOML,
                    DiscoveryEngine.ServeStyle.FOLDER, new Properties());

            InPlaceStatus status = status(outcome);
            assertEquals(1, status.pending().size(),
                    "Forge " + host.getKey() + " must queue the 1.18.2 jar, not load it as current");
            assertFalse(status.isHidden(rankine),
                    "Forge would load it, so it stays visible until a translation exists");
        }
    }

    @Test
    @DisplayName("a failed translation is recorded and not retried, so it cannot ask for restarts forever")
    void failedTranslationIsNotRetried() throws IOException {
        Path game = game();
        Path oldJar = old(game, "broken.jar");

        assertEquals(0, translate(forge(game), (source, out) -> null));
        DiscoveryEngine.Outcome second = forge(game);

        InPlaceStatus status = status(second);
        assertEquals(List.of(), status.pending(), "a failure waits for a new Retromod or file");
        assertEquals(1, status.failed().size());
        assertTrue(status.failed().get(0).hidden(),
                "a refused original stays away from the loader so it cannot crash the game");
        assertEquals(List.of(oldJar), second.hide());
        assertEquals(0, translate(second, (source, out) -> fail("must not retry")));
    }

    @Test
    @DisplayName("a disk problem during translation is retried on the next launch")
    void ioFailureIsRetried() throws IOException {
        Path game = game();
        old(game, "flaky.jar");

        translate(forge(game), (source, out) -> {
            throw new IOException("disk full");
        });

        assertEquals(1, status(forge(game)).pending().size());
    }

    @Test
    @DisplayName("an I/O failure that never clears becomes a failure after three launches")
    void repeatedIoFailureSettles() throws IOException {
        Path game = game();
        old(game, "unreadable.jar");
        InPlaceLaunch.Translator broken = (source, out) -> {
            throw new IOException("invalid archive");
        };

        for (int launch = 1; launch <= InPlaceLaunch.MAX_TRANSLATION_ATTEMPTS; launch++) {
            translate(forge(game), broken);
        }

        InPlaceStatus settled = status(forge(game));
        assertEquals(List.of(), settled.pending(), "it must not be retried on every launch");
        assertEquals(1, settled.failed().size());
    }

    @Test
    @DisplayName("a translation that kills the game every launch ends as a failure, not a loop")
    void translationThatKillsTheGameSettles() throws IOException {
        Path game = game();
        Path oldJar = old(game, "huge.jar");
        InPlaceLaunch.Translator dies = (source, out) -> {
            throw new OutOfMemoryError("simulated");
        };

        for (int launch = 1; launch <= InPlaceLaunch.MAX_TRANSLATION_ATTEMPTS; launch++) {
            DiscoveryEngine.Outcome outcome = forge(game);
            assertEquals(List.of(oldJar), outcome.hide(), "launch " + launch);
            assertThrows(OutOfMemoryError.class, () -> translate(outcome, dies));
        }

        DiscoveryEngine.Outcome settled = forge(game);
        InPlaceStatus status = status(settled);
        assertEquals(List.of(), status.pending(), "it must not be translated on every launch");
        assertEquals(1, status.failed().size());
        assertEquals(List.of(oldJar), settled.hide(), "a refused original stays held back");
        assertEquals(0, translate(settled, (source, out) -> fail("must not retry")));
    }

    @Test
    @DisplayName("a translation that overflows the stack is a failure at once")
    void stackOverflowIsAFailure() throws IOException {
        Path game = game();
        old(game, "deep.jar");

        assertEquals(0, translate(forge(game), (source, out) -> {
            throw new StackOverflowError("simulated");
        }));

        InPlaceStatus status = status(forge(game));
        assertEquals(List.of(), status.pending());
        assertEquals(1, status.failed().size());
    }

    @Test
    @DisplayName("an old jar linked into mods/ is left to the loader instead of held back")
    void linkedOldJarIsLeftToTheLoader() throws IOException {
        Path game = game();
        Path stored = old(tmp.resolve("store"), "linked.jar");
        Path link = game.resolve("mods/linked.jar");
        try {
            Files.createSymbolicLink(link, stored);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable here");
        }

        for (int launch = 1; launch <= DiscoveryEngine.MAX_HIDDEN_LAUNCHES + 1; launch++) {
            DiscoveryEngine.Outcome outcome = forge(game);
            assertEquals(List.of(), outcome.hide(),
                    "translation refuses links, so holding it back would hide it for nothing");
            assertEquals(List.of(), status(outcome).pending(), "launch " + launch);
        }
        assertTrue(log.stream().anyMatch(line -> line.contains("linked.jar is a link")),
                log.toString());
    }

    @Test
    @DisplayName("a held-back mod is shown to the loader after three launches without translation")
    void stopsHidingWhenTranslationNeverRuns() throws IOException {
        Path game = game();
        Path oldJar = old(game, "stuck.jar");

        for (int launch = 1; launch <= DiscoveryEngine.MAX_HIDDEN_LAUNCHES; launch++) {
            assertEquals(List.of(oldJar), forge(game).hide(), "launch " + launch);
        }
        DiscoveryEngine.Outcome shown = forge(game);
        assertEquals(List.of(), shown.hide(),
                "the loader must see it once so its own message names the file");
        assertTrue(log.stream().anyMatch(line -> line.contains("held back stuck.jar")));
        assertEquals(List.of(oldJar), forge(game).hide(), "the count starts over afterwards");
    }

    @Test
    @DisplayName("removing a mod from mods/ prunes its entry and its translated file")
    void prunesARemovedMod() throws IOException {
        Path game = game();
        Path oldJar = old(game, "gone.jar");
        translate(forge(game), copyTranslator("x"));
        Path jars = game.toRealPath().resolve("retromod-cache/translated/forge/1.20.1/jars");
        assertTrue(Files.exists(jars.resolve("gone-retromod.jar")));

        Files.delete(oldJar);
        DiscoveryEngine.Outcome after = forge(game);

        assertFalse(Files.exists(jars.resolve("gone-retromod.jar")));
        assertNull(after.serveFolder());
        try (TranslatedModCache cache = TranslatedModCache.attach(game, "forge", "1.20.1",
                java.time.Duration.ZERO)) {
            assertEquals(Map.of(), cache.entries());
        }
    }

    @Test
    @DisplayName("of two old copies of one mod, only the newest is translated")
    void duplicateOldCopiesKeepTheNewest() throws IOException {
        Path game = game();
        Path older = TestModJars.forgeMod(game.resolve("mods/dup-1.0.0.jar"), "dup", "1.0.0",
                "[1.19.2,1.20)");
        Path newer = TestModJars.forgeMod(game.resolve("mods/dup-1.2.0.jar"), "dup", "1.2.0",
                "[1.19.2,1.20)");

        DiscoveryEngine.Outcome outcome = forge(game);

        InPlaceStatus status = status(outcome);
        assertEquals(List.of(newer.toAbsolutePath().normalize().toString()),
                status.pending().stream().map(InPlaceStatus.Source::source).toList());
        assertEquals(1, status.superseded().size());
        assertTrue(outcome.hide().contains(older));
    }

    @Test
    @DisplayName("an old original beside a current copy of the same mod is held back, not translated")
    void currentCopySupersedesTheOldOriginal() throws IOException {
        Path game = game();
        Path oldJar = TestModJars.forgeMod(game.resolve("mods/same.jar"), "same", "1.0",
                "[1.19,)");
        TestModJars.stampedForgeMod(game.resolve("mods/same-retromod.jar"), "same", "[1.19,)",
                "Retromod-Transformed", "true");

        DiscoveryEngine.Outcome outcome = forge(game);

        assertEquals(List.of(oldJar), outcome.hide(),
                "two files with one mod id would leave the choice to the loader");
        assertEquals(List.of(), status(outcome).pending());
    }

    @Test
    @DisplayName("jars Retromod already translated, including in-game picker output, are left alone")
    void legacyTranslatedJarsAreLeftAlone() throws IOException {
        Path game = game();
        TestModJars.stampedForgeMod(game.resolve("mods/rewritten.jar"), "rewritten",
                "[1.19.2,1.20)", "Retromod-Transformed", "true");
        TestModJars.stampedForgeMod(game.resolve("mods/picked-retromod.jar"), "picked",
                "[1.19.2,1.20)", "Retromod-AOT-Version", "1.3.4");

        DiscoveryEngine.Outcome outcome = forge(game);

        assertEquals(List.of(), outcome.hide());
        assertEquals(List.of(), status(outcome).pending());
    }

    @Test
    @DisplayName("a jar translated for another Minecraft version is named and left as it is")
    void translatedForAnotherVersionIsNamed() throws IOException {
        Path game = game();
        translatedFor(game.resolve("mods/shared-retromod.jar"), "shared", "1.19.2");
        translatedFor(game.resolve("mods/here-retromod.jar"), "here", FORGE_HOST);

        DiscoveryEngine.Outcome outcome = forge(game);

        assertEquals(List.of(), outcome.hide(), "a warning only; the jar is not held back");
        assertTrue(log.stream().anyMatch(line -> line.startsWith("warn: shared-retromod.jar was "
                + "translated by Retromod for Minecraft 1.19.2")), log.toString());
        assertTrue(log.stream().noneMatch(line -> line.contains("here-retromod.jar was")),
                "a jar translated for this version is not mentioned");
    }

    /** A jar stamped the way ForgeModTransformer stamps its output for {@code minecraft}. */
    private static void translatedFor(Path file, String modId, String minecraft)
            throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Retromod-Transformed", "true");
        manifest.getMainAttributes().putValue("Retromod-Target-Version", minecraft);
        TestModJars.jar(file, manifest, Map.of("META-INF/mods.toml", TestModJars.toml(modId,
                "1.0.0", "[1.19,)", "forge", "[40,)", "mandatory=true")));
    }

    @Test
    @DisplayName("retromod-input/ jars are translated into the cache and never moved or hidden")
    void inputFolderJarsAreTranslatedInPlace() throws IOException {
        Path game = game();
        Path input = TestModJars.forgeMod(game.resolve("retromod-input/input.jar"), "input",
                "1.0", "[1.20.1,1.21)");

        DiscoveryEngine.Outcome first = forge(game);
        assertEquals(List.of(), first.hide(), "the loader never scans retromod-input/");
        assertEquals("retromod-input", status(first).pending().get(0).location());
        translate(first, copyTranslator("in"));

        DiscoveryEngine.Outcome second = forge(game);
        assertNotNull(second.serveFolder());
        assertTrue(Files.exists(input), "in-place mode never moves an input");
        assertFalse(Files.exists(game.resolve("retromod-input/processed")));
    }

    @Test
    @DisplayName("an older plain Retromod jar in mods/ is kept out in both modes")
    void staleRetromodRuntimeIsHidden() throws IOException {
        Path game = game();
        Path stale = TestModJars.forgeMod(game.resolve("mods/retromod-1.3.4+1.20.1.jar"),
                "retromod", "1.3.4", "[1.20.1]");
        Properties rewrite = new Properties();
        rewrite.setProperty(InPlaceSettings.SYSTEM_PROPERTY, "true");

        assertEquals(List.of(stale), forge(game).hide());
        assertEquals(List.of(stale), forge(game, rewrite).hide());
    }

    @Test
    @DisplayName("rewrite mode leaves the cache alone and holds back nothing new")
    void rewriteModeTouchesNoCache() throws IOException {
        Path game = game();
        old(game, "old.jar");
        Properties rewrite = new Properties();
        rewrite.setProperty(InPlaceSettings.SYSTEM_PROPERTY, "true");

        DiscoveryEngine.Outcome outcome = forge(game, rewrite);

        assertEquals(InPlaceStatus.MODE_REWRITE, outcome.mode());
        assertEquals(List.of(), outcome.hide(), "rewrite mode behaves as before");
        assertNull(outcome.statusFile());
        assertFalse(Files.exists(game.resolve("retromod-cache")));
    }

    @Test
    @DisplayName("switching back to rewrite mode holds back refused originals in-place mode handled")
    void rewriteModeHoldsBackPreviouslyHandledOriginals() throws IOException {
        Path game = game();
        Path refused = old(game, "refused.jar");
        Path accepted = TestModJars.forgeMod(game.resolve("mods/accepted.jar"), "accepted",
                "1.0", "[1.19,)");
        forge(game);
        Properties rewrite = new Properties();
        rewrite.setProperty(InPlaceSettings.SYSTEM_PROPERTY, "true");

        DiscoveryEngine.Outcome outcome = forge(game, rewrite);

        assertEquals(List.of(refused), outcome.hide(),
                "without this the loader stops before Retromod can rewrite the file");
        assertFalse(outcome.hide().contains(accepted));
    }

    @Test
    @DisplayName("rewrite mode never holds back a jar its rewrite flow would not rewrite")
    void rewriteModeOnlyHoldsBackWhatItRewrites() throws IOException {
        Path game = game();
        old(game, "retromod-addon-1.0.jar");
        forge(game);
        Properties rewrite = new Properties();
        rewrite.setProperty(InPlaceSettings.SYSTEM_PROPERTY, "true");

        assertEquals(List.of(), forge(game, rewrite).hide(),
                "the rewrite flow skips names starting with retromod, so holding this back "
                        + "would hide it on every launch");
    }

    @Test
    @DisplayName("changing a setting that changes output throws the old translations away")
    void outputSettingChangeRetranslates() throws IOException {
        Path game = game();
        old(game, "setting.jar");
        translate(forge(game), copyTranslator("polyfills-on"));
        assertEquals(1, status(forge(game)).served().size());

        Path config = game.resolve("config/retromod/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"polyfills_enabled\": false}");

        InPlaceStatus after = status(forge(game));
        assertEquals(List.of(), after.served());
        assertEquals(1, after.pending().size());
    }

    @Test
    @DisplayName("a game directory reached through a link still uses the cache")
    void linkedGameDirectoryWorks() throws IOException {
        Path real = tmp.resolve("real-game");
        Files.createDirectories(real.resolve("mods"));
        Path link = tmp.resolve("linked-game");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable here");
        }
        Path oldJar = old(link, "linked.jar");

        DiscoveryEngine.Outcome outcome = forge(link);

        assertEquals(InPlaceStatus.MODE_IN_PLACE, outcome.mode());
        assertEquals(List.of(oldJar), outcome.hide(),
                "hidden paths keep the loader's own spelling, link and all");
    }

    @Test
    @DisplayName("a bootstrap and runtime from different builds turn the pass off")
    void protocolMismatchTurnsThePassOff() throws IOException {
        Map<String, Object> request = new HashMap<>();
        request.put("protocol", DiscoveryEngine.PROTOCOL + 1);
        request.put("gameDir", game());

        Map<String, Object> result = DiscoveryEngine.run(request);

        assertEquals("error", result.get("mode"));
    }

    @Test
    @DisplayName("the map entry point returns JDK types the bootstrap can read")
    void mapEntryPointRoundTrips() throws IOException {
        Path game = game();
        Path oldJar = old(game, "map.jar");
        Map<String, Object> request = new HashMap<>();
        request.put("protocol", DiscoveryEngine.PROTOCOL);
        request.put("gameDir", game);
        request.put("modsDir", game.resolve("mods"));
        request.put("modsListing", List.of(oldJar));
        request.put("loader", "forge");
        request.put("minecraft", FORGE_HOST);
        request.put("loaderBuild", "47.4.26");
        request.put("hostToml", ForgeModMetadata.FORGE_TOML);
        request.put("serveStyle", "folder");
        request.put("properties", new Properties());

        Map<String, Object> result = DiscoveryEngine.run(request);

        assertEquals(InPlaceStatus.MODE_IN_PLACE, result.get("mode"));
        assertEquals(List.of(oldJar), result.get("hide"));
        assertInstanceOf(Path.class, result.get("statusFile"));
    }
}
