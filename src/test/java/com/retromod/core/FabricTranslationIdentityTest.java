/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A runtime setting change rebuilds Fabric jars from {@code retromod-input/processed/}. */
class FabricTranslationIdentityTest {

    private static final String HOST = "26.3";
    private static final String JAR = "fixture-retromod.jar";

    @TempDir
    Path game;

    private String savedHost;
    private Path mods;
    private Path loaded;
    private Path pending;

    @BeforeEach
    void setUp() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        FabricTranslationIdentity.forgetScheduledForTesting();
        mods = game.resolve("mods");
        loaded = mods.resolve(JAR);
        pending = game.resolve(FabricTranslationIdentity.PENDING_FOLDER).resolve(JAR);
    }

    @AfterEach
    void tearDown() {
        FabricTranslationIdentity.forgetScheduledForTesting();
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("Pre-launch never rewrites a jar that Fabric already loaded for this launch")
    void loadedJarKeepsItsBytes() throws Exception {
        translateWithRuntimeOn();
        byte[] before = Files.readAllBytes(loaded);

        assertEquals(1, launchWithRuntimeOff(), "the jar built with the runtime is translated again");
        assertArrayEquals(before, Files.readAllBytes(loaded),
                "the jar Fabric loaded at launch must keep its bytes until the game exits");
        assertTrue(Files.isRegularFile(pending), "the rebuilt jar waits in the pending folder");
        assertNull(attribute(pending), "the rebuilt jar is built without the runtime");
        assertTrue(FabricTranslationIdentity.isReplacedOnExit(JAR));
        assertTrue(RetromodPreLaunch.getTransformedMods().contains(JAR), "it asks for the usual restart");
    }

    // Fabric already loaded the old jar, so the rebuild waits in retromod-input/pending/ for exit.
    @Test
    @DisplayName("The rebuilt jar replaces the loaded one when the game exits, in both directions")
    void exitInstallsTheRebuild() throws Exception {
        String onIdentity = translateWithRuntimeOn();

        try (var off = LoaderApiTestSupport.runtimeOff()) {
            assertEquals(1, prepareAndRetranslate());
            assertEquals(0, RetromodPreLaunch.retranslateForLoaderApiSetting(game, HOST),
                    "a jar already scheduled is not rebuilt twice in one launch");
            FabricTranslationIdentity.installScheduled();
            assertNull(attribute(loaded), "after exit, mods/ holds the jar built without the runtime");
            assertFalse(Files.exists(pending));
            assertFalse(FabricTranslationIdentity.isReplacedOnExit(JAR));
            assertEquals(0, RetromodPreLaunch.retranslateForLoaderApiSetting(game, HOST),
                    "a jar that matches the setting is left alone");
        }

        try (var on = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())) {
            assertEquals(1, prepareAndRetranslate());
            FabricTranslationIdentity.installScheduled();
            assertEquals(onIdentity, attribute(loaded));
        }
        assertTrue(Files.exists(game.resolve("retromod-input/processed/fixture.jar")),
                "the original stays in processed/");
    }

    @Test
    @DisplayName("A rebuild the exit could not install waits, and asks for no second restart")
    void pendingJarWaitsWhileTheOldOneIsLoaded() throws Exception {
        translateWithRuntimeOn();
        byte[] before = Files.readAllBytes(loaded);
        try (var off = LoaderApiTestSupport.runtimeOff()) {
            assertEquals(1, prepareAndRetranslate());
            // The process ended without the exit hook, or the replace failed.
            FabricTranslationIdentity.forgetScheduledForTesting();
            assertEquals(0, RetromodPreLaunch.retranslateForLoaderApiSetting(game, HOST),
                    "the next launch schedules the waiting copy again without a new restart prompt");
            assertTrue(FabricTranslationIdentity.isReplacedOnExit(JAR));
            assertArrayEquals(before, Files.readAllBytes(loaded));
            FabricTranslationIdentity.installScheduled();
            assertNull(attribute(loaded));
        }
    }

    @Test
    @DisplayName("A waiting rebuild whose old jar is gone is installed at once and asks for a restart")
    void pendingJarIsInstalledWhenTheOldOneIsGone() throws Exception {
        translateWithRuntimeOn();
        try (var off = LoaderApiTestSupport.runtimeOff()) {
            assertEquals(1, prepareAndRetranslate());
            FabricTranslationIdentity.forgetScheduledForTesting();
            // The user removed the old jar while the game was closed.
            Files.delete(loaded);
            assertEquals(1, RetromodPreLaunch.retranslateForLoaderApiSetting(game, HOST));
            assertNull(attribute(loaded));
            assertFalse(Files.exists(pending));
        }
    }

    @Test
    @DisplayName("A waiting rebuild that no longer matches the setting is dropped")
    void stalePendingJarIsDropped() throws Exception {
        String onIdentity = translateWithRuntimeOn();
        try (var off = LoaderApiTestSupport.runtimeOff()) {
            assertEquals(1, prepareAndRetranslate());
        }
        FabricTranslationIdentity.forgetScheduledForTesting();
        try (var on = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())) {
            assertEquals(0, RetromodPreLaunch.retranslateForLoaderApiSetting(game, HOST),
                    "the loaded jar already matches the setting again");
            assertFalse(Files.exists(pending), "the copy built for the other setting is deleted");
            assertEquals(onIdentity, attribute(loaded));
        }
    }

    @Test
    @DisplayName("A mismatched jar without its original is kept and reported")
    void missingOriginalIsKept() throws Exception {
        Files.createDirectories(mods);
        Path original = game.resolve("elsewhere.jar");
        Files.write(original, LoaderApiFixtures.fabricJar());
        try (var on = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())) {
            RetromodPreLaunch.registerShimsForTransform(HOST);
            new FabricModTransformer(HOST).transformMod(original, mods);
        }
        Path translated = mods.resolve("elsewhere-retromod.jar");
        byte[] before = Files.readAllBytes(translated);
        List<String> rebuilt = FabricTranslationIdentity.retranslateMismatched(mods,
                game.resolve(FabricTranslationIdentity.PENDING_FOLDER),
                List.of(game.resolve("retromod-input/processed")), null,
                (source, outputDir) -> { throw new AssertionError("nothing to rebuild from"); });
        assertTrue(rebuilt.isEmpty());
        assertArrayEquals(before, Files.readAllBytes(translated));
    }

    /** Translates the fixture into {@code mods/} with the runtime on, and returns its identity. */
    private String translateWithRuntimeOn() throws Exception {
        Path processed = Files.createDirectories(game.resolve("retromod-input/processed"));
        Files.createDirectories(mods);
        Path original = processed.resolve("fixture.jar");
        Files.write(original, LoaderApiFixtures.fabricJar());
        try (var on = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())) {
            RetromodPreLaunch.registerShimsForTransform(HOST);
            new FabricModTransformer(HOST).transformMod(original, mods);
        }
        String identity = attribute(loaded);
        assertTrue(identity != null && identity.startsWith("fabric-26x/"));
        return identity;
    }

    private int launchWithRuntimeOff() throws Exception {
        try (var off = LoaderApiTestSupport.runtimeOff()) {
            return prepareAndRetranslate();
        }
    }

    /** What pre-launch does: registers shims for the host, then checks every translated jar. */
    private int prepareAndRetranslate() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodPreLaunch.registerShimsForTransform(HOST);
        return RetromodPreLaunch.retranslateForLoaderApiSetting(game, HOST);
    }

    private static String attribute(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest().getMainAttributes().getValue(LoaderApiRuntime.MANIFEST_ATTRIBUTE);
        }
    }
}
