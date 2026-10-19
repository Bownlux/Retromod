/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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

    /**
     * #358 (Project Rankine): the Forge MDK template writes {@code [[mods]] #mandatory}. Missing
     * that header made the mod read as its last dependency, with the comment left in its version.
     */
    @Test
    void readsTheModFromAnMdkHeaderWithATrailingComment() throws Exception {
        ModVersionInfo info = ModVersionDetector.metadataOnly().detectVersion(
                mdkTemplateJar("META-INF/mods.toml", "forge", "[36.0.1,)", "1.3.8-hf2", null));

        assertEquals("rankine", info.modId());
        assertEquals("1.3.8-hf2", info.modVersion());
    }

    /**
     * #358: a 1.18.2 jar whose toml has no minecraft dependency, a mod version without a Minecraft
     * prefix, and a forge range left at the 1.16.5 floor. Only the manifest names the version.
     * With no version, in-place discovery left it to Forge 1.21.1 untranslated.
     */
    @Test
    void readsTheSourceVersionFromTheManifestWhenTheTomlHasNone() throws Exception {
        ModVersionInfo info = ModVersionDetector.metadataOnly().detectVersion(mdkTemplateJar(
                "META-INF/mods.toml", "forge", "[36.0.1,)", "1.3.8-hf2", "1.18.2-1.3.8-hf2"));

        assertEquals("1.18.2", info.targetMcVersion(),
                "the forge range [36.0.1,) is a 1.16.5 floor and must not be read instead");
        assertTrue(info.needsTransformation("1.21.1"));
        assertTrue(info.needsTransformation("26.3"));
    }

    @Test
    void resolvesAJarVersionPlaceholderThroughTheManifest() throws Exception {
        ModVersionInfo info = ModVersionDetector.metadataOnly().detectVersion(mdkTemplateJar(
                "META-INF/mods.toml", "forge", "[47,)", "${file.jarVersion}", "1.20.1-2.4.0"));

        assertEquals("1.20.1", info.targetMcVersion());
    }

    @Test
    void readsTheManifestForANeoForgeTomlWithoutLoaderOrMinecraftDependency() throws Exception {
        ModVersionInfo info = ModVersionDetector.metadataOnly().detectVersion(mdkTemplateJar(
                "META-INF/neoforge.mods.toml", null, null, "0.1.0", "1.21.1-0.1.0"));

        assertEquals("neoforge", info.modLoaderType());
        assertEquals("1.21.1", info.targetMcVersion());
        assertTrue(info.needsTransformation("26.3"));
    }

    @Test
    void aPlainManifestVersionStillNamesNoMinecraftVersion() throws Exception {
        ModVersionInfo info = ModVersionDetector.metadataOnly().detectVersion(mdkTemplateJar(
                "META-INF/mods.toml", "forge", "[36.0.1,)", "1.0.0", "1.0.0"));

        assertNull(info.targetMcVersion(),
                "a mod's own semver in the manifest is not a Minecraft version");
        assertFalse(info.needsTransformation("1.21.1"));
    }

    @Test
    void theTomlMinecraftDependencyWinsOverTheManifest() throws Exception {
        Path jar = tmp.resolve("declared.jar");
        String toml = mdkTemplateToml("forge", "[47,)", "2.0.0")
                + "[[dependencies.rankine]]\n    modId=\"minecraft\"\n    mandatory=true\n"
                + "    versionRange=\"[1.20.1,1.21)\"\n";
        writeJar(jar, "META-INF/mods.toml", toml, "1.18.2-2.0.0");

        ModVersionInfo info = ModVersionDetector.metadataOnly().detectVersion(jar);

        assertEquals("1.20.1", info.targetMcVersion());
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
        writeJar(jar, "META-INF/mods.toml", toml, null);
        return jar;
    }

    /** A toml shaped like the Forge MDK template, comments included, as Project Rankine ships it. */
    private Path mdkTemplateJar(String tomlEntry, String loaderId, String loaderRange,
            String version, String implementationVersion) throws Exception {
        Path jar = Files.createTempFile(tmp, "rankine-", ".jar");
        writeJar(jar, tomlEntry, mdkTemplateToml(loaderId, loaderRange, version),
                implementationVersion);
        return jar;
    }

    private static String mdkTemplateToml(String loaderId, String loaderRange, String version) {
        String toml = """
                # This is an example mods.toml file. It contains the data relating to the loading mods.
                modLoader="javafml" #mandatory
                license="MIT License"
                loaderVersion="[36,)" #mandatory

                [[mods]] #mandatory
                # The modid of the mod
                modId="rankine" #mandatory
                version="%s" #mandatory
                 # A display name for the mod
                displayName="Project Rankine" #mandatory
                description='''
                A mod with worldgen and an alloying system
                '''
                """.formatted(version);
        if (loaderId != null) {
            toml += """
                    [[dependencies.rankine]]
                        modId="%s"
                        mandatory=true
                        versionRange="%s"
                    """.formatted(loaderId, loaderRange);
        }
        return toml;
    }

    private static void writeJar(Path jar, String tomlEntry, String toml,
            String implementationVersion) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (implementationVersion != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION,
                    implementationVersion);
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry(tomlEntry));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
