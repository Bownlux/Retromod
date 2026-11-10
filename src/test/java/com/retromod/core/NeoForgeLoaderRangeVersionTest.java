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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #251 (Immersive Vehicles): a NeoForge mod that declares no {@code minecraft} dependency.
 *
 * <p>The {@code neoforge} range already pins the Minecraft version, so plenty of mods leave the
 * {@code minecraft} block out. Retromod read only that block, so the mod came back with no version,
 * and a mod with no version is skipped rather than transformed. It then reached the game untouched
 * and failed on the first class Minecraft had moved, with nothing in the log to say Retromod had
 * passed it over.
 */
class NeoForgeLoaderRangeVersionTest {

    @TempDir
    Path tmp;

    /** The range under test can be arbitrarily long, so it must not shape the file name. */
    private int jarCounter;

    /** Some bounds are read differently on a 26.x host, so each test restores the host it found. */
    private String savedHost;

    @BeforeEach
    void saveHost() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restoreHost() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    private Path neoForgeJar(String neoforgeRange, String modVersion) throws Exception {
        String toml = """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"
                [[mods]]
                modId="immersivevehicles"
                version="%s"
                displayName="Immersive Vehicles"
                [[dependencies.immersivevehicles]]
                modId="neoforge"
                type="required"
                versionRange="%s"
                """.formatted(modVersion, neoforgeRange);
        Path jar = tmp.resolve("mod-" + (jarCounter++) + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private String detect(String neoforgeRange) throws Exception {
        return new ModVersionDetector().detectVersion(neoForgeJar(neoforgeRange, "1.3.0"))
                .targetMcVersion();
    }

    @Test
    @DisplayName("a 1.x NeoForge range names the Minecraft version it was built for")
    void derivesFromTheOnePointXLine() throws Exception {
        assertEquals("1.21.1", detect("[21.1.79,)"));
        assertEquals("1.20.2", detect("[20.2.86,)"));
        assertEquals("1.21.1", detect("[21.1,)"),
                "a two component bound still pins the Minecraft minor and patch");
    }

    @Test
    @DisplayName("a 26.x NeoForge range is the Minecraft version already")
    void derivesFromTheUnprefixedLine() throws Exception {
        assertEquals("26.1.2", detect("[26.1.2.101,)"),
                "from 26 the leading three components are the Minecraft version, not a build");
        assertEquals("26.2", detect("[26.2,)"));
    }

    @Test
    @DisplayName("the mod is transformed instead of silently skipped")
    void theModIsNoLongerSkipped() throws Exception {
        ModVersionInfo info = new ModVersionDetector()
                .detectVersion(neoForgeJar("[21.1.79,)", "1.3.0"));

        assertEquals("immersivevehicles", info.modId());
        assertEquals("1.21.1", info.targetMcVersion());
        assertTrue(info.needsTransformation("26.2"),
                "with no version the mod was skipped and reached the game untranslated (#251)");
    }

    @Test
    @DisplayName("a Forge numbered range says nothing about the Minecraft version")
    void declinesAForgeNumberedRange() throws Exception {
        // NeoForge's 1.20.1 builds ship under the Forge coordinates and are numbered 47.x. Reading
        // that as a Minecraft version would produce 1.47, which is worse than no answer: it feeds
        // the shim chain a version that does not exist.
        assertNull(detect("[47.1.3,)"));
        assertNull(detect("[47,)"), "a single component pins nothing");
    }

    @Test
    @DisplayName("a hostile range cannot put a path or unbounded text into the version")
    void refusesHostileRanges() throws Exception {
        // The range comes out of a mod's own metadata, so it is attacker text. Anything echoed
        // through verbatim would carry into the derived version, which is compared, logged, and
        // used to pick a shim chain. Every component is reparsed and the result rebuilt from
        // numbers, so the answer is digits and dots or nothing.
        for (String hostile : new String[]{
                "[26.2.x/../../../..,)",     // a path separator
                "[21../../../../../etc,)",   // an empty component then a path
                "[21.1 /etc/passwd,)",       // whitespace and a path
                "[21." + "9".repeat(4000) + ",)",  // an unbounded component
                "[+21.1,)",                  // a sign, which Integer.parseInt would accept
                "[-21.1,)",
                "[\u0661\u0662.1,)"}) {     // non-ASCII digits, which parseInt also accepts
            assertNull(detect(hostile),
                    "a version must never be derived from " + hostile.replace("\n", ""));
        }
    }

    @Test
    @DisplayName("on a 26.x host a hostile pre-release bound still yields digits and dots or nothing")
    void refusesHostileRangesOnA26Host() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        for (String hostile : new String[]{
                "[26.2.x/../../../..,)", "[21../../../../../etc,)", "[21.1 /etc/passwd,)",
                "[21." + "9".repeat(4000) + ",)", "[+21.1,)", "[-21.1,)", "[\u0661\u0662.1,)",
                "[21.-beta,)", "[21.0.0-beta/../..,)", "[21." + "9".repeat(4000) + "-beta,)"}) {
            String version = detect(hostile);
            assertTrue(version == null || version.matches("\\d+(\\.\\d+)*"),
                    "a hostile bound gave " + version);
        }
    }

    @Test
    @DisplayName("on NeoForge 26.x a beta 1.x-line bound names the Minecraft version")
    void betaBoundOnThe1xLineIsReadOnA26Host() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        // Cloth Config 15.0.140 and AppleSkin for 1.21 declare only these, and break on 26.3.
        assertEquals("1.21.0", detect("[21.0.110-beta,)"));
        assertEquals("1.21.0", detect("[21.0.0-beta,)"));
        assertEquals("1.20.4", detect("[20.4.72-beta,)"));
        assertTrue(new ModVersionDetector().detectVersion(neoForgeJar("[21.0.110-beta,)", "15.0.140"))
                .needsTransformation("26.3"), "the library is translated instead of left broken");
    }

    @Test
    @DisplayName("on NeoForge 1.21.1 a beta bound stays unknown, so a native jar is left alone")
    void betaBoundStaysUnknownOnA1xHost() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        // EMI 1.1.24 for 1.21.1 declares only [20.4.72-beta,). Reading it as 1.20.4 would
        // translate a jar that runs as it is, the trap TerraBlender's [20.4,) was.
        assertNull(detect("[21.0.110-beta,)"));
        assertNull(detect("[21.0.0-beta,)"));
        assertNull(detect("[20.4.72-beta,)"));
    }

    @Test
    @DisplayName("a 26.x-line bound reads the same on every host")
    void unprefixedBoundIsReadTheSameOnEveryHost() throws Exception {
        for (String host : new String[]{"1.21.1", "26.3"}) {
            RetromodVersion.TARGET_MC_VERSION = host;
            assertEquals("26.3.0", detect("[26.3.0.43-beta,)"), "host " + host);
            assertNull(detect("[26.1.0-beta,)"), "host " + host);
        }
    }

    @Test
    @DisplayName("an explicit minecraft dependency still wins")
    void explicitMinecraftBlockTakesPrecedence() throws Exception {
        String toml = """
                modLoader="javafml"
                loaderVersion="[1,)"
                [[mods]]
                modId="example"
                version="1.0.0"
                [[dependencies.example]]
                modId="neoforge"
                versionRange="[21.1.79,)"
                [[dependencies.example]]
                modId="minecraft"
                versionRange="[1.20.4,1.20.4]"
                """;
        Path jar = tmp.resolve("explicit.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertEquals("1.20.4", new ModVersionDetector().detectVersion(jar).targetMcVersion(),
                "the loader range is a fallback, never an override");
    }
}
