/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hold My Items declares {@code "minecraft": "~1.21.11-"} (#281). */
class ModVersionDetectorPrereleaseRangeTest {

    @Test
    void dropsFabricsPrereleaseMarkerFromTheMinecraftRange(@TempDir Path directory) throws Exception {
        assertEquals("1.21.11", detect(directory, "~1.21.11-").targetMcVersion(),
                "the trailing hyphen only admits prereleases, and kept it hid the version from the shim chain");
    }

    @Test
    void keepsAPrereleaseVersionThatHasASuffix(@TempDir Path directory) throws Exception {
        assertEquals("1.21.11-rc.1", detect(directory, ">=1.21.11-rc.1").targetMcVersion());
    }

    private static ModVersionInfo detect(Path directory, String range) throws Exception {
        Path jar = directory.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(("{\"schemaVersion\":1,\"id\":\"fixture\",\"version\":\"1.0\","
                    + "\"depends\":{\"minecraft\":\"" + range + "\"}}").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return new ModVersionDetector().detectVersion(jar);
    }
}
