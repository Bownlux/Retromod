/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.retromod.embedder.ModVersionInfo;

/** Covers old Forge metadata that has loader and library dependencies but no Minecraft block. */
class ForgeMissingMinecraftMetadataTest {
    @TempDir
    Path tmp;

    @Test
    void infersSourceVersionWithoutMistakingDependencyForTheMod() throws Exception {
        ModVersionInfo info = new ModVersionDetector().detectVersion(forgeJar("1.16.4-0.3.9"));

        assertEquals("betterportals", info.modId());
        assertEquals("1.16.4-0.3.9", info.modVersion());
        assertEquals("1.16.4", info.targetMcVersion());
        assertTrue(info.needsTransformation("1.20.1"));
    }

    @Test
    void doesNotTreatPlainModSemverAsMinecraftVersion() throws Exception {
        ModVersionInfo info = new ModVersionDetector().detectVersion(forgeJar("1.3.0"));

        assertEquals("betterportals", info.modId());
        assertNull(info.targetMcVersion());
    }

    private Path forgeJar(String version) throws Exception {
        String toml = """
                modLoader="javafml"
                loaderVersion="[35,)"
                license="MIT"
                [[mods]]
                modId="betterportals"
                version="%s"
                displayName="Better Portals"
                [[dependencies.betterportals]]
                modId="forge"
                mandatory=true
                versionRange="[35,)"
                [[dependencies.betterportals]]
                modId="yungsapi"
                mandatory=true
                versionRange="[1.16.4-Forge-6,)"
                """.formatted(version);
        Path jar = tmp.resolve("betterportals-" + version + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
