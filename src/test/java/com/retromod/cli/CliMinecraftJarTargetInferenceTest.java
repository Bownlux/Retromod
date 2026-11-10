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

        withCliTarget("26.1", false, () -> {
            RetromodCli.configureTargetFromMinecraftJar(jar);

            assertEquals("26.2", cliTarget());
            assertEquals("26.2", RetromodVersion.TARGET_MC_VERSION);
        });
    }

    @Test
    void rejectsExplicitTargetThatDisagreesWithMinecraftJar() throws Exception {
        Path jar = versionJar(tempDir.resolve("target.jar"), "26.2");

        withCliTarget("26.3", true, () -> {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> RetromodCli.configureTargetFromMinecraftJar(jar));

            // The error names both versions, as docs/cli.md says.
            assertTrue(error.getMessage().contains("--target 26.3"), error.getMessage());
            assertTrue(error.getMessage().contains("does not match Minecraft JAR version 26.2"),
                    error.getMessage());
        });
    }

    @Test
    void keepsExplicitTargetWhenJarVersionIsUnreadable() throws Exception {
        Path jar = tempDir.resolve("custom-client.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // No version.json and no standard client filename, so the version cannot be read.
        }

        withCliTarget("26.3", true, () -> {
            RetromodCli.configureTargetFromMinecraftJar(jar);

            assertEquals("26.3", cliTarget(), "an unreadable jar version leaves --target in charge");
            assertEquals("26.3", RetromodVersion.TARGET_MC_VERSION);
        });
    }

    @Test
    void acceptsReleaseCandidateJarForExplicitTarget() throws Exception {
        Path jar = versionJar(tempDir.resolve("target.jar"), "26.3-rc-1");

        withCliTarget("26.3", true, () -> {
            RetromodCli.configureTargetFromMinecraftJar(jar);

            assertEquals("26.3", cliTarget(), "a 26.3 release candidate jar matches --target 26.3");
        });
    }

    /** Something a test runs that may throw. */
    private interface CliAction {
        void run() throws Exception;
    }

    /** Runs {@code action} with the CLI target set as main would, then restores the old state. */
    private static void withCliTarget(String target, boolean explicit, CliAction action)
            throws Exception {
        Field targetField = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        Field explicitField = RetromodCli.class.getDeclaredField("targetMcVersionExplicit");
        targetField.setAccessible(true);
        explicitField.setAccessible(true);
        String savedCliTarget = (String) targetField.get(null);
        boolean savedExplicit = explicitField.getBoolean(null);
        String savedSharedTarget = RetromodVersion.TARGET_MC_VERSION;
        try {
            targetField.set(null, target);
            explicitField.setBoolean(null, explicit);
            RetromodVersion.TARGET_MC_VERSION = target;
            action.run();
        } finally {
            targetField.set(null, savedCliTarget);
            explicitField.setBoolean(null, savedExplicit);
            RetromodVersion.TARGET_MC_VERSION = savedSharedTarget;
        }
    }

    private static String cliTarget() throws Exception {
        Field field = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        field.setAccessible(true);
        return (String) field.get(null);
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
