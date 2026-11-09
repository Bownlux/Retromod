/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import com.retromod.inplace.InPlaceLaunch;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Rewrite mode says in latest.log why it could not update an old jar in mods/. */
class RewriteModeFailureTest {

    /** One entry point's rewrite helper. */
    @FunctionalInterface
    interface Rewriter {
        boolean rewrite(Path modJar, Path backupDir, InPlaceLaunch.LinkingTranslator translator,
                SyntheticLibraries libraries);
    }

    static Stream<Arguments> entryPoints() {
        return Stream.of(
                Arguments.of("Forge", (Rewriter) RetromodForge::rewriteJarInMods),
                Arguments.of("NeoForge", (Rewriter) RetromodNeoForge::rewriteJarInMods));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void failedTranslationWarnsWithItsCause(String loader, Rewriter rewriter, @TempDir Path game)
            throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        byte[] original = jarHolding("original");
        Path oldMod = Files.write(mods.resolve("oldmod.jar"), original);
        AtomicBoolean result = new AtomicBoolean(true);

        String log = LoaderApiTestSupport.captureErr(() -> result.set(rewriter.rewrite(oldMod,
                mods.resolve("retromod-backups"), (source, out, libraries) -> {
                    throw new IOException("injected disk full");
                }, SyntheticLibraries.NONE)));

        assertFalse(result.get());
        assertArrayEquals(original, Files.readAllBytes(oldMod), "the old jar stays as it was");
        assertEquals(1, warnings(log).size(), "one warning per failed jar: " + log);
        String warning = warnings(log).get(0);
        assertTrue(warning.contains("oldmod.jar") && warning.contains("injected disk full"),
                "the warning names the jar and the cause: " + warning);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void linkedJarIsNotTranslatedAndSaysWhy(String loader, Rewriter rewriter, @TempDir Path game)
            throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        byte[] original = jarHolding("linked");
        Path target = Files.write(game.resolve("elsewhere.jar"), original);
        Path link = Files.createSymbolicLink(mods.resolve("oldmod.jar"), target);
        AtomicInteger translations = new AtomicInteger();
        AtomicBoolean result = new AtomicBoolean(true);

        String log = LoaderApiTestSupport.captureErr(() -> result.set(rewriter.rewrite(link,
                mods.resolve("retromod-backups"), (source, out, libraries) -> {
                    translations.incrementAndGet();
                    return null;
                }, SyntheticLibraries.NONE)));

        assertFalse(result.get());
        assertEquals(0, translations.get(), "a linked jar is refused before translation");
        assertArrayEquals(original, Files.readAllBytes(target), "the link target is unchanged");
        assertEquals(1, warnings(log).size(), log);
        String warning = warnings(log).get(0);
        assertTrue(warning.contains("oldmod.jar") && warning.contains("symlink"),
                "the warning names the jar and the link: " + warning);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void translatedJarReplacesTheOldOneQuietly(String loader, Rewriter rewriter, @TempDir Path game)
            throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        byte[] original = jarHolding("original");
        byte[] translated = jarHolding("translated");
        Path oldMod = Files.write(mods.resolve("oldmod.jar"), original);
        AtomicBoolean result = new AtomicBoolean(false);

        String log = LoaderApiTestSupport.captureErr(() -> result.set(rewriter.rewrite(oldMod,
                mods.resolve("retromod-backups"),
                (source, out, libraries) -> Files.write(out.resolve("oldmod-retromod.jar"), translated),
                SyntheticLibraries.NONE)));

        assertTrue(result.get());
        assertArrayEquals(translated, Files.readAllBytes(oldMod), "mods/ holds the translation");
        assertArrayEquals(original,
                Files.readAllBytes(mods.resolve("retromod-backups/oldmod.jar")),
                "the backup holds the original");
        assertTrue(warnings(log).isEmpty(), "a success prints no warning: " + log);
    }

    private static byte[] jarHolding(String text) throws IOException {
        return LoaderApiFixtures.jar(Map.of("note.txt", text.getBytes(StandardCharsets.UTF_8)));
    }

    private static java.util.List<String> warnings(String log) {
        return log.lines().filter(line -> line.contains("WARN")).toList();
    }
}
