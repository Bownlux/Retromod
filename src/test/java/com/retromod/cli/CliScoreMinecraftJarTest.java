/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** score indexes --mc-jar, or the installed client jar of the target version, never a fixed path. */
class CliScoreMinecraftJarTest {

    private static final String PRISM_MAC = "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft";
    private static final String PRISM_LINUX = ".local/share/PrismLauncher/libraries/com/mojang/minecraft";

    @TempDir
    Path home;

    private Field target;
    private String savedTarget;
    private String savedHome;

    @BeforeEach
    void useTempHome() throws Exception {
        // The Windows lookup reads APPDATA from the environment, which a test cannot redirect.
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        target = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        target.setAccessible(true);
        savedTarget = (String) target.get(null);
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restore() throws Exception {
        if (target == null) return;
        target.set(null, savedTarget);
        System.setProperty("user.home", savedHome);
    }

    @Test
    void findsInstalledJarForTargetVersion() throws Exception {
        plantPrismClient("26.3");
        target.set(null, "26.3");

        Path found = RetromodCli.scoreMinecraftJar(null);

        assertTrue(found != null && found.startsWith(home),
                "score should find the 26.3 client jar under the launcher folder: " + found);
        assertEquals("minecraft-26.3-client.jar", found.getFileName().toString());
    }

    @Test
    void ignoresOldDeveloperPrerelease() throws Exception {
        plantPrismClient("26.1-pre-2");
        target.set(null, "26.1");

        assertNull(RetromodCli.scoreMinecraftJar(null),
                "a 26.1 target must not pick up a 26.1-pre-2 jar from a fixed developer path");
    }

    @Test
    void explicitMcJarWins() throws Exception {
        plantPrismClient("26.3");
        target.set(null, "26.3");
        Path explicit = home.resolve("chosen-client.jar");

        assertEquals(explicit, RetromodCli.scoreMinecraftJar(explicit));
    }

    /** Writes an empty client jar for {@code version} where Prism keeps it on macOS and Linux. */
    private void plantPrismClient(String version) throws Exception {
        for (String root : new String[]{PRISM_MAC, PRISM_LINUX}) {
            Path jar = home.resolve(root).resolve(version)
                    .resolve("minecraft-" + version + "-client.jar");
            Files.createDirectories(jar.getParent());
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
                // An empty jar is enough: the lookup only checks that the file exists.
            }
        }
    }
}
