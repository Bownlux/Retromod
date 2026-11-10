/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A mod's saved data is read from the version it was built for, and data read as older than it is gets fixes it already
 * had: from 1.12.2, melon becomes melon_slice and sharpness becomes protection. Some declared floors lag far behind the
 * build, so the jar's loader requirement and file name count too.
 */
class ModBuildVersionTest {

    private static final String FORGE_TOML = "META-INF/mods.toml";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path directory;

    @BeforeEach
    void host() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
    }

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.setModSourceVersion(null);
    }

    @Test
    @DisplayName("Entity Model Features' [1,) floor gives way to the 1.20.1 its file name and Forge floor name")
    void entityModelFeatures() throws Exception {
        String toml = "modLoader = \"javafml\"\nloaderVersion = \"[33,)\"\n[[mods]]\nmodId = \"entity_model_features\"\n"
                + "[[dependencies.entity_model_features]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[33,)\"\n"
                + "[[dependencies.entity_model_features]]\nmodId = \"minecraft\"\nmandatory = true\nversionRange = \"[1,)\"\n";

        assertEquals("1.20.1", build("entity_model_features-3.3.12-1.20.1-forge.jar", FORGE_TOML, toml));
        assertEquals("1.16.2", build("emf.jar", FORGE_TOML, toml), "without its name, Forge 33's version is left");
    }

    @Test
    @DisplayName("EuphoriaPatcher's [1.13,) floor stays, since neither its Forge floor nor its name names a newer version")
    void euphoriaPatcher() throws Exception {
        String toml = "modLoader=\"javafml\" #mandatory\nloaderVersion=\"[10,)\"\n[[mods]] #mandatory\nmodId=\"euphoria_patcher\"\n"
                + "[[dependencies.euphoria_patcher]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[10,)\"\n"
                + "[[dependencies.euphoria_patcher]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.13,)\"\n";

        assertEquals("1.13", build("EuphoriaPatcher-1.10.5-r5.9.3-forge.jar", FORGE_TOML, toml));
    }

    @Test
    @DisplayName("a Forge requirement newer than the declared floor wins")
    void forgeFloorWins() throws Exception {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"wide\"\n"
                + "[[dependencies.wide]]\nmodId=\"minecraft\"\nversionRange=\"[1.16.5,)\"\n";

        assertEquals("1.20.1", build("wide.jar", FORGE_TOML, toml));
    }

    @Test
    @DisplayName("a NeoForge 1.20.4 mod's neoforge requirement names 1.20.4")
    void neoForgeRequirement() throws Exception {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[2,)\"\n[[mods]]\nmodId=\"neo\"\n"
                + "[[dependencies.neo]]\nmodId=\"neoforge\"\nversionRange=\"[20.4,)\"\n"
                + "[[dependencies.neo]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.2,)\"\n";

        assertEquals("1.20.4", build("neo.jar", FORGE_TOML, toml, "neoforge"));
    }

    @Test
    @DisplayName("NeoForge's own FML floor and its neoforge.mods.toml each name a lowest version")
    void neoForgeLoaderFloor() throws Exception {
        String fml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"neo\"\n"
                + "[[dependencies.neo]]\nmodId=\"neoforge\"\nversionRange=\"*\"\n"
                + "[[dependencies.neo]]\nmodId=\"minecraft\"\nversionRange=\"[1.14,)\"\n";
        String newer = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"neo\"\n";

        assertEquals("1.20.2", build("neo.jar", FORGE_TOML, fml, "neoforge"), "FML 1 is NeoForge for 1.20.2");
        assertEquals("1.20.5", build("neo.jar", "META-INF/neoforge.mods.toml", newer));
    }

    @Test
    @DisplayName("a jar built for several loaders is read from the metadata of the loader it is translated for")
    void multiLoaderJar() throws Exception {
        String forge = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"both\"\n"
                + "[[dependencies.both]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.1,1.21)\"\n";
        String neo = "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n[[mods]]\nmodId=\"both\"\n"
                + "[[dependencies.both]]\nmodId=\"minecraft\"\nversionRange=\"[1.21,)\"\n";
        Path jar = directory.resolve("both-1.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String[] entry : new String[][]{{FORGE_TOML, forge}, {"META-INF/neoforge.mods.toml", neo}}) {
                out.putNextEntry(new JarEntry(entry[0]));
                out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        ModVersionDetector forForge = ModVersionDetector.metadataOnly().preferring("forge");

        assertEquals("1.20.1", forForge.buildVersion(jar, forForge.detectVersion(jar).targetMcVersion()),
                "Forge reads only mods.toml, so the NeoForge build's 1.20.5 floor does not count");
    }

    @Test
    @DisplayName("a mods.toml serving Forge and NeoForge at once counts its neoforge lines only for NeoForge")
    void sharedTomlNeoForgeLines() throws Exception {
        // Terralith 1.20.x declares forge [46,) and neoforge [20.2,) in one mods.toml.
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"terralith\"\n"
                + "[[dependencies.terralith]]\nmodId=\"forge\"\nversionRange=\"[46,)\"\n"
                + "[[dependencies.terralith]]\nmodId=\"neoforge\"\nversionRange=\"[20.2,)\"\n"
                + "[[dependencies.terralith]]\nmodId=\"minecraft\"\nversionRange=\"[1.20,1.21)\"\n";
        Path jar = jar("terralith.jar", FORGE_TOML, toml);

        assertEquals("1.20", ModVersionDetector.metadataOnly().preferring("forge").buildVersion(jar, "1.20"));
        assertEquals("1.20.2", ModVersionDetector.metadataOnly().preferring("neoforge").buildVersion(jar, "1.20"));
    }

    @Test
    @DisplayName("a Fabric mod is never older than 1.14, and its file name can name a newer version")
    void fabricFloor() throws Exception {
        String json = "{\"schemaVersion\":1,\"id\":\"old\",\"version\":\"1.0\",\"depends\":{\"minecraft\":\">=1.8\"}}";

        assertEquals("1.14", declaredBuild("old.jar", "fabric.mod.json", json, "1.8"));
        assertEquals("1.20.1", declaredBuild("old-mc1.20.1.jar", "fabric.mod.json", json, "1.8"));
    }

    @Test
    @DisplayName("a 1.12.2 mod keeps its version, and a jar that names none stays unknown")
    void oldAndUnknown() throws Exception {
        String info = "[{\"modid\":\"old\",\"mcversion\":\"1.12.2\"}]";
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"*\"\n[[mods]]\nmodId=\"universal\"\n";

        assertEquals("1.12.2", declaredBuild("old.jar", "mcmod.info", info, "1.12.2"));
        assertNull(declaredBuild("universal.jar", FORGE_TOML, toml, null), "an unknown mod keeps 1.20.1's data version");
    }

    @Test
    @DisplayName("the transformer reads its build version from the jar and forgets it with the source version")
    void transformerKeepsTheBuildVersion() throws Exception {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[33,)\"\n[[mods]]\nmodId=\"etf\"\n"
                + "[[dependencies.etf]]\nmodId=\"minecraft\"\nversionRange=\"[1,)\"\n";
        Path jar = jar("entity_texture_features-7.2.6-1.20.1-forge.jar", FORGE_TOML, toml);

        transformer.setModSourceVersion("1.13");
        transformer.setModSourceJar(jar);
        assertEquals("1.20.1", transformer.getModBuildVersion());
        assertEquals("1.13", transformer.getModSourceVersion(), "the shim chain still starts at the declared floor");

        transformer.setModSourceVersion("1.16.5");
        assertEquals("1.16.5", transformer.getModBuildVersion(), "the next mod starts from its own source version");
    }

    /** The build version of a jar holding one metadata file, read as a path would after detecting its version. */
    private String build(String fileName, String entry, String metadata) throws Exception {
        return build(fileName, entry, metadata, loaderOf(entry));
    }

    /** The same, translated for {@code loader}. */
    private String build(String fileName, String entry, String metadata, String loader) throws Exception {
        Path jar = jar(fileName, entry, metadata);
        ModVersionDetector detector = ModVersionDetector.metadataOnly().preferring(loader);
        return detector.buildVersion(jar, detector.detectVersion(jar).targetMcVersion());
    }

    private String declaredBuild(String fileName, String entry, String metadata, String declared) throws Exception {
        return ModVersionDetector.metadataOnly().preferring(loaderOf(entry)).buildVersion(jar(fileName, entry, metadata), declared);
    }

    private static String loaderOf(String entry) {
        if (entry.equals("fabric.mod.json")) return "fabric";
        return entry.equals("META-INF/neoforge.mods.toml") ? "neoforge" : "forge";
    }

    private Path jar(String fileName, String entry, String metadata) throws Exception {
        Path jar = directory.resolve(fileName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
