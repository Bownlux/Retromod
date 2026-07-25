/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #174: multi-version mods that run NATIVELY on the host (declaring a Maven range whose finite
 * upper bound contains it, e.g. {@code [1.19,1.20.1]} on a 1.20.1 host) were detected as their
 * LOWER bound ("a 1.19 mod"), transformed, and broken alongside everything they dragged down
 * (16-mod report). Such a mod now reads as host-version, so {@code needsTransformation} skips it
 * on every path. An OPEN upper bound ({@code [1.19,)}) keeps the old lower-bound behavior: on a
 * 26.x host that 1.19 mod genuinely needs translation, and skipping it would regress the entire
 * old-mod use case.
 */
class ModVersionRangeContainmentTest {

    @TempDir
    Path tmp;

    private String savedHost;

    @BeforeEach
    void saveHost() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restoreHost() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    private Path forgeModJar(String versionRange) throws Exception {
        String toml = """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="testmod"
                version="1.0.0"
                displayName="Test Mod"
                [[dependencies.testmod]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                ordering="NONE"
                side="BOTH"
                [[dependencies.testmod]]
                modId="minecraft"
                mandatory=true
                versionRange="%s"
                ordering="NONE"
                side="BOTH"
                """.formatted(versionRange);
        Path jar = tmp.resolve("testmod-" + versionRange.hashCode() + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("META-INF/mods.toml"));
            jos.write(toml.getBytes());
            jos.closeEntry();
        }
        return jar;
    }

    private ModVersionInfo detect(String range, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        return new ModVersionDetector().detectVersion(forgeModJar(range));
    }

    @Test
    @DisplayName("#174: [1.19,1.20.1] on a 1.20.1 host reads as native, no transform")
    void finiteRangeContainingHostSkips() throws Exception {
        ModVersionInfo info = detect("[1.19,1.20.1]", "1.20.1");
        assertNotNull(info);
        assertEquals("1.20.1", info.targetMcVersion(),
                "a finite range containing the host must read as host-native");
        assertFalse(info.needsTransformation("1.20.1"),
                "and must therefore not be transformed");
    }

    @Test
    @DisplayName("#174: exclusive upper [1.19,1.20.1) on 1.20.1 does NOT contain the host")
    void exclusiveUpperExcludesHost() throws Exception {
        ModVersionInfo info = detect("[1.19,1.20.1)", "1.20.1");
        assertNotNull(info);
        assertEquals("1.19", info.targetMcVersion(),
                "an exclusive upper bound at the host keeps lower-bound detection");
    }

    @Test
    @DisplayName("open range [1.19,) keeps lower-bound detection (a 26.x host must still translate it)")
    void openRangeKeepsLowerBound() throws Exception {
        ModVersionInfo info = detect("[1.19,)", "26.1");
        assertNotNull(info);
        assertEquals("1.19", info.targetMcVersion(),
                "author-optimism open ranges must not suppress translation of genuinely old mods");
        assertTrue(info.needsTransformation("26.1"));
    }

    @Test
    @DisplayName("finite range far below the host keeps lower-bound detection")
    void rangeBelowHostStillTransforms() throws Exception {
        ModVersionInfo info = detect("[1.19,1.20.4]", "26.1");
        assertNotNull(info);
        assertEquals("1.19", info.targetMcVersion());
        assertTrue(info.needsTransformation("26.1"),
                "a 1.19-1.20.4 mod on a 26.1 host still needs translation");
    }

    @Test
    @DisplayName("#174 report shape: packetfixer-style [1.18,1.20.4] on 1.20.1 reads native")
    void multiVersionModOnMiddleHostSkips() throws Exception {
        ModVersionInfo info = detect("[1.18,1.20.4]", "1.20.1");
        assertNotNull(info);
        assertEquals("1.20.1", info.targetMcVersion(),
                "a host inside the finite range is native even when not the upper bound");
        assertFalse(info.needsTransformation("1.20.1"));
    }
}
