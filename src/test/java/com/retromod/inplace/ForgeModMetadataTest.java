/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-place loading holds an old original back only when the loader would refuse or skip it
 * anyway. A wrong "would load" stops the launch before Retromod runs; a wrong "would not"
 * only costs one restart, so every unclear case reads as "would not".
 */
class ForgeModMetadataTest {

    private static Optional<ForgeModMetadata> facts(String toml) {
        return Optional.of(ForgeModMetadata.parse(ForgeModMetadata.FORGE_TOML, toml, null));
    }

    private static String mod(String minecraftBlock) {
        return "modLoader=\"javafml\" #mandatory\nloaderVersion=\"[43,)\"\n"
                + "[[mods]] #mandatory\nmodId=\"examplemod\" #mandatory\nversion=\"1.2.3\"\n"
                + "description='''\nA [[mods]] lookalike = \"inside a string\"\n'''\n"
                + minecraftBlock;
    }

    @Test
    @DisplayName("reads mod ids, version, and dependency rules from a real-shaped toml")
    void readsModsAndDependencies() {
        ForgeModMetadata metadata = facts(mod("[[dependencies.examplemod]]\n"
                + "    modId=\"minecraft\"\n    mandatory=true\n"
                + "    versionRange=\"[1.19.2,1.20)\" # comment\n"
                + "[[dependencies.examplemod]]\nmodId=\"jei\"\nmandatory=false\n"
                + "versionRange=\"[11,)\"\n[[mods]]\nmodId=\"second\"\n")).orElseThrow();

        assertEquals(List.of("examplemod", "second"), metadata.modIds());
        assertEquals("1.2.3", metadata.firstVersion());
        assertEquals(2, metadata.dependencies().size());
        assertEquals("[1.19.2,1.20)", metadata.dependencies().get(0).versionRange());
        assertEquals(ForgeModMetadata.Requirement.OPTIONAL,
                metadata.dependencies().get(1).requirement());
    }

    @Test
    @DisplayName("${file.jarVersion} is resolved from the manifest, like the loader does")
    void resolvesJarVersionFromTheManifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "4.5.6");
        ForgeModMetadata metadata = ForgeModMetadata.parse(ForgeModMetadata.FORGE_TOML,
                "[[mods]]\nmodId=\"x\"\nversion=\"${file.jarVersion}\"\n", manifest);
        assertEquals("4.5.6", metadata.firstVersion());
    }

    @Test
    @DisplayName("a Minecraft range that leaves out the host means the loader refuses the jar")
    void refusesARangeWithoutTheHost() {
        Optional<ForgeModMetadata> old = facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.19.2,1.20)\"\n"));
        assertFalse(ForgeModMetadata.loaderWouldLoad(old, ForgeModMetadata.FORGE_TOML, "1.20.1",
                "47.4.26"));
        assertTrue(ForgeModMetadata.loaderWouldLoad(old, ForgeModMetadata.FORGE_TOML, "1.19.3",
                "44.1.0"));
    }

    @Test
    @DisplayName("a jar without the toml the host reads is skipped by the loader")
    void missingHostTomlIsSkipped() {
        assertFalse(ForgeModMetadata.loaderWouldLoad(Optional.empty(),
                ForgeModMetadata.NEOFORGE_TOML, "26.3", "26.3.0.43-beta"));
    }

    @Test
    @DisplayName("NeoForge has no forge mod, so a required forge dependency is refused")
    void requiredForgeDependencyIsRefusedOnNeoForge() {
        Optional<ForgeModMetadata> needsForge = facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"));
        assertFalse(ForgeModMetadata.loaderWouldLoad(needsForge,
                ForgeModMetadata.NEOFORGE_TOML, "26.3", "26.3.0.43-beta"));
        assertTrue(ForgeModMetadata.loaderWouldLoad(needsForge,
                ForgeModMetadata.FORGE_TOML, "1.20.1", "47.4.26"));
    }

    @Test
    @DisplayName("open ranges, wildcards, and incompatibility blocks do not refuse the host")
    void acceptsWhatTheLoaderAccepts() {
        assertTrue(ForgeModMetadata.loaderWouldLoad(facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"minecraft\"\nversionRange=\"[1.19,)\"\n")),
                ForgeModMetadata.FORGE_TOML, "26.3", "66.0.12"));
        assertTrue(ForgeModMetadata.loaderWouldLoad(facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"minecraft\"\nversionRange=\"*\"\n")),
                ForgeModMetadata.FORGE_TOML, "26.3", "66.0.12"));
        assertTrue(ForgeModMetadata.loaderWouldLoad(facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"minecraft\"\ntype=\"incompatible\"\nversionRange=\"[1.12,1.13)\"\n")),
                ForgeModMetadata.NEOFORGE_TOML, "26.3", "26.3.0.43-beta"));
    }

    @Test
    @DisplayName("the top-level language provider and loaderVersion are read")
    void readsTheLanguageProvider() {
        ForgeModMetadata metadata = facts(mod("")).orElseThrow();
        assertEquals("javafml", metadata.modLoader());
        assertEquals("[43,)", metadata.loaderVersion());
    }

    @Test
    @DisplayName("a loaderVersion that leaves out the host's javafml means the loader refuses the jar")
    void boundedLoaderVersionIsRefused() {
        Optional<ForgeModMetadata> bounded = facts(
                "modLoader=\"javafml\"\nloaderVersion=\"[43,44)\"\n[[mods]]\nmodId=\"m\"\n");
        Optional<ForgeModMetadata> open = facts(
                "modLoader=\"javafml\"\nloaderVersion=\"[43,)\"\n[[mods]]\nmodId=\"m\"\n");
        Optional<ForgeModMetadata> sameMajor = facts(
                "modLoader=\"javafml\"\nloaderVersion=\"[47,48)\"\n[[mods]]\nmodId=\"m\"\n");

        assertFalse(wouldLoadOnForge(bounded, "54.1.22"), "javafml 54 is outside [43,44)");
        assertTrue(wouldLoadOnForge(open, "54.1.22"));
        assertTrue(wouldLoadOnForge(sameMajor, "47.4.26"));
        assertFalse(wouldLoadOnForge(bounded, "unknown"),
                "an upper bound cannot be checked without the host's javafml version");
        assertTrue(wouldLoadOnForge(open, "unknown"),
                "javafml only grows, so a lower bound an old mod met still holds");
        assertFalse(ForgeModMetadata.loaderWouldLoad(facts(
                "modLoader=\"javafml\"\nloaderVersion=\"[1,2)\"\n[[mods]]\nmodId=\"m\"\n"),
                ForgeModMetadata.NEOFORGE_TOML, "26.3", "26.3.0.43-beta"),
                "NeoForge's javafml is the FML version, which is not the NeoForge version");
    }

    @Test
    @DisplayName("a required loader or library range the host may not meet means the jar is held back")
    void boundedRequiredRangesAreRefused() {
        Optional<ForgeModMetadata> oldForge = facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"forge\"\nmandatory=true\nversionRange=\"[43,44)\"\n"));
        Optional<ForgeModMetadata> boundedLibrary = facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"jei\"\nmandatory=true\nversionRange=\"[11.0,12.0)\"\n"));
        Optional<ForgeModMetadata> optionalLibrary = facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"jei\"\nmandatory=false\nversionRange=\"[11.0,12.0)\"\n"));
        Optional<ForgeModMetadata> openLibrary = facts(mod("[[dependencies.examplemod]]\n"
                + "modId=\"jei\"\nmandatory=true\nversionRange=\"[11.0,)\"\n"));
        Optional<ForgeModMetadata> neoForge = Optional.of(ForgeModMetadata.parse(
                ForgeModMetadata.NEOFORGE_TOML, "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n"
                        + "[[mods]]\nmodId=\"m\"\n[[dependencies.m]]\nmodId=\"neoforge\"\n"
                        + "type=\"required\"\nversionRange=\"[21.1.0,21.2)\"\n", null));

        assertFalse(wouldLoadOnForge(oldForge, "54.1.22"));
        assertTrue(wouldLoadOnForge(oldForge, "43.4.0"));
        assertFalse(wouldLoadOnForge(boundedLibrary, "54.1.22"),
                "the installed version is unknown, so a bounded range may refuse it");
        assertFalse(wouldLoadOnForge(optionalLibrary, "54.1.22"),
                "the loader checks an optional range too when that mod is installed");
        assertTrue(wouldLoadOnForge(openLibrary, "54.1.22"));
        assertFalse(ForgeModMetadata.loaderWouldLoad(neoForge, ForgeModMetadata.NEOFORGE_TOML,
                "26.3", "26.3.0.43-beta"));
        assertTrue(ForgeModMetadata.loaderWouldLoad(neoForge, ForgeModMetadata.NEOFORGE_TOML,
                "1.21.1", "21.1.252"));
    }

    private static boolean wouldLoadOnForge(Optional<ForgeModMetadata> facts, String build) {
        return ForgeModMetadata.loaderWouldLoad(facts, ForgeModMetadata.FORGE_TOML, "1.19.2",
                build);
    }

    @Test
    @DisplayName("version ranges: unions, pins, open ends, and anything unreadable as no")
    void versionRanges() {
        assertTrue(MavenVersionRange.certainlyContains("[1.20.1]", "1.20.1"));
        assertFalse(MavenVersionRange.certainlyContains("[1.20.1]", "1.20.2"));
        assertTrue(MavenVersionRange.certainlyContains("[1.19,1.19.3),[1.20,)", "26.3"));
        assertFalse(MavenVersionRange.certainlyContains("[1.19,1.19.3),[1.20,1.21)", "1.19.4"));
        assertFalse(MavenVersionRange.certainlyContains("(1.20.1,1.21)", "1.20.1"));
        assertTrue(MavenVersionRange.certainlyContains("[1.20.1,1.21]", "1.21"));
        assertTrue(MavenVersionRange.certainlyContains("1.20.1", "1.20.1"));
        assertFalse(MavenVersionRange.certainlyContains("1.20", "1.20.1"),
                "a bare version is only read as the version it names");
        assertFalse(MavenVersionRange.certainlyContains("[1.20,", "1.20.1"));
        assertFalse(MavenVersionRange.certainlyContains("[abc,)", "1.20.1"));
        assertFalse(MavenVersionRange.certainlyContains(null, "1.20.1"));
        assertFalse(MavenVersionRange.certainlyContains("[20230615000000,)", "1.20.1"),
                "a number too large to compare reads as no instead of failing the pass");
        assertTrue(MavenVersionRange.openAbove("[43,)"));
        assertTrue(MavenVersionRange.openAbove("[1.19,1.19.3),[1.20,)"));
        assertTrue(MavenVersionRange.openAbove("*"));
        assertFalse(MavenVersionRange.openAbove("[43,44)"));
        assertFalse(MavenVersionRange.openAbove("43"), "a bare version is not read as open");
    }
}
