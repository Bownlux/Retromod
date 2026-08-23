package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for the Fabric side of the CurseForge-export folder (#78): the
 * {@code mods/Retromod/} drain ({@link RetromodPreLaunch#drainReadyModsFolder})
 * and the {@code -Dfabric.addMods} guard
 * ({@link RetromodPreLaunch#fabricAddModsCovers}) that keeps option #1 (drain +
 * restart) and option #2 (load in-place via JVM arg) from colliding.
 */
class RetromodPreLaunchCfFolderTest {

    @Test
    void drainMovesOnlyJarsIntoModsFolder(@TempDir Path gameDir) throws IOException {
        Path retromod = Files.createDirectories(gameDir.resolve("mods").resolve("Retromod"));
        Path mods = gameDir.resolve("mods");
        Path alpha = createJar(retromod.resolve("alpha.jar"), "new-alpha");
        byte[] expectedAlpha = Files.readAllBytes(alpha);
        createJar(retromod.resolve("beta.jar"), "beta");
        createJar(mods.resolve("alpha.jar"), "old-alpha");
        Files.createFile(retromod.resolve("README.txt"));   // not a jar → left in place

        int moved = RetromodPreLaunch.drainReadyModsFolder(retromod, mods);

        assertEquals(2, moved, "both jars should be moved");
        assertTrue(Files.exists(mods.resolve("alpha.jar")), "alpha.jar landed in mods/");
        assertTrue(Files.exists(mods.resolve("beta.jar")), "beta.jar landed in mods/");
        assertArrayEquals(expectedAlpha, Files.readAllBytes(mods.resolve("alpha.jar")),
                "the complete staged archive should replace the installed copy");
        assertFalse(Files.exists(retromod.resolve("alpha.jar")), "alpha.jar moved out of the subfolder");
        assertFalse(Files.exists(retromod.resolve("beta.jar")), "beta.jar moved out of the subfolder");
        assertTrue(Files.exists(retromod.resolve("README.txt")), "non-jar files are left alone");
    }

    @Test
    void malformedReadyJarKeepsInstalledArchive(@TempDir Path gameDir) throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Path retromod = Files.createDirectories(mods.resolve("Retromod"));
        Path installed = createJar(mods.resolve("example.jar"), "installed");
        byte[] installedBytes = Files.readAllBytes(installed);
        Path malformed = retromod.resolve("example.jar");
        Files.writeString(malformed, "not a jar", StandardCharsets.UTF_8);

        int moved = RetromodPreLaunch.drainReadyModsFolder(retromod, mods);

        assertEquals(0, moved, "a malformed archive must not be published");
        assertArrayEquals(installedBytes, Files.readAllBytes(installed),
                "the installed archive must remain unchanged");
        assertTrue(Files.exists(malformed), "the rejected source must remain staged");
    }

    @Test
    void truncatedReadyJarIsNotPublished(@TempDir Path gameDir) throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Path retromod = Files.createDirectories(mods.resolve("Retromod"));
        Path truncated = createJar(retromod.resolve("partial.jar"), "partial");
        byte[] complete = Files.readAllBytes(truncated);
        Files.write(truncated, Arrays.copyOf(complete, complete.length / 2));

        int moved = RetromodPreLaunch.drainReadyModsFolder(retromod, mods);

        assertEquals(0, moved, "a partial archive must not be published");
        assertFalse(Files.exists(mods.resolve("partial.jar")),
                "a rejected partial archive must not create an installed output");
        assertTrue(Files.exists(truncated), "the rejected source must remain staged");
    }

    @Test
    void drainOnAbsentFolderIsANoOp(@TempDir Path gameDir) {
        Path absent = gameDir.resolve("mods").resolve("Retromod");
        assertEquals(0, RetromodPreLaunch.drainReadyModsFolder(absent, gameDir.resolve("mods")));
    }

    @Test
    void addModsGuardMatchesRelativeAndAbsolutePaths(@TempDir Path gameDir) {
        Path folder = gameDir.resolve("mods").resolve("Retromod");
        String prev = System.getProperty("fabric.addMods");
        try {
            System.clearProperty("fabric.addMods");
            assertFalse(RetromodPreLaunch.fabricAddModsCovers(gameDir, folder),
                "unset property → not covered");

            // relative entry, resolved against the game dir
            System.setProperty("fabric.addMods", "mods/Retromod");
            assertTrue(RetromodPreLaunch.fabricAddModsCovers(gameDir, folder),
                "relative mods/Retromod resolves to the folder");

            // absolute entry
            System.setProperty("fabric.addMods", folder.toAbsolutePath().toString());
            assertTrue(RetromodPreLaunch.fabricAddModsCovers(gameDir, folder),
                "absolute path matches");

            // a different folder, among multiple entries
            System.setProperty("fabric.addMods",
                gameDir.resolve("somewhere-else").toString() + java.io.File.pathSeparator + "mods/other");
            assertFalse(RetromodPreLaunch.fabricAddModsCovers(gameDir, folder),
                "unrelated paths → not covered");
        } finally {
            if (prev == null) {
                System.clearProperty("fabric.addMods");
            } else {
                System.setProperty("fabric.addMods", prev);
            }
        }
    }

    private static Path createJar(Path path, String payload) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("payload.txt"));
            output.write(payload.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }
}
