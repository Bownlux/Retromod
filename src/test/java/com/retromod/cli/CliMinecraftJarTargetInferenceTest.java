/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures --mc-jar and version-gated offline repairs use the same target version. */
class CliMinecraftJarTargetInferenceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsOfficialVersionJson() throws Exception {
        Path jar = versionJar(tempDir.resolve("renamed-target.jar"), "26.2");
        assertEquals("26.2", RetromodCli.inferMinecraftVersion(jar));
    }

    @Test
    void fallsBackToOfficialClientFilename() throws Exception {
        Path jar = tempDir.resolve("minecraft-26.2-rc-1-client.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // A valid empty JAR exercises the filename path.
        }

        assertEquals("26.2-rc-1", RetromodCli.inferMinecraftVersion(jar));
    }

    @Test
    void configuresSharedTargetWhenTargetWasNotExplicit() throws Exception {
        Path jar = versionJar(tempDir.resolve("target.jar"), "26.2");
        Field target = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        Field explicit = RetromodCli.class.getDeclaredField("targetMcVersionExplicit");
        target.setAccessible(true);
        explicit.setAccessible(true);
        String savedCliTarget = (String) target.get(null);
        boolean savedExplicit = explicit.getBoolean(null);
        String savedSharedTarget = RetromodVersion.TARGET_MC_VERSION;
        try {
            target.set(null, "26.1");
            explicit.setBoolean(null, false);
            RetromodVersion.TARGET_MC_VERSION = "26.1";

            RetromodCli.configureTargetFromMinecraftJar(jar);

            assertEquals("26.2", target.get(null));
            assertEquals("26.2", RetromodVersion.TARGET_MC_VERSION);
        } finally {
            target.set(null, savedCliTarget);
            explicit.setBoolean(null, savedExplicit);
            RetromodVersion.TARGET_MC_VERSION = savedSharedTarget;
        }
    }

    @Test
    void rejectsExplicitTargetThatDisagreesWithMinecraftJar() throws Exception {
        Path jar = versionJar(tempDir.resolve("target.jar"), "26.2");
        Field target = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        Field explicit = RetromodCli.class.getDeclaredField("targetMcVersionExplicit");
        target.setAccessible(true);
        explicit.setAccessible(true);
        String savedCliTarget = (String) target.get(null);
        boolean savedExplicit = explicit.getBoolean(null);
        try {
            target.set(null, "26.1");
            explicit.setBoolean(null, true);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> RetromodCli.configureTargetFromMinecraftJar(jar));

            assertTrue(error.getMessage().contains("does not match Minecraft JAR version 26.2"));
        } finally {
            target.set(null, savedCliTarget);
            explicit.setBoolean(null, savedExplicit);
        }
    }

    private static Path versionJar(Path jar, String version) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("version.json"));
            output.write(("{\"id\":\"" + version + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
