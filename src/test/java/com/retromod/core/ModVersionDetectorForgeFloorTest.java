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
 * 27 of 200 Forge 1.20.1 mods declare no minecraft dependency, so their version read as unknown
 * and no path translated them. On Forge 26.2 in-place loading then left Clumps and Jade to the
 * loader untranslated, and their SRG-named mixins stopped the game before Retromod could run.
 */
class ModVersionDetectorForgeFloorTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path directory;

    @BeforeEach
    void host() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
    }

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @Test
    @DisplayName("the file name's Minecraft version wins over a lagging loader floor")
    void fileNameRefinesTheFloor() throws Exception {
        String clumps = "modLoader=\"javafml\"\nloaderVersion=\"[41,)\"\n[[mods]]\nmodId=\"clumps\"\n";
        assertEquals("1.20.1", forge("Clumps-forge-1.20.1-12.0.0.4.jar", clumps));
        assertEquals("1.19", forge("clumps.jar", clumps), "a renamed jar still has its Forge 41 floor");
    }

    @Test
    @DisplayName("a forge dependency names its Forge major's Minecraft version")
    void forgeDependencyFloor() throws Exception {
        String jade = "modLoader=\"javafml\"\nloaderVersion=\"[46,)\"\n[[mods]]\nmodId=\"jade\"\n"
                + "[[dependencies.jade]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[46, )\"\n";
        assertEquals("1.20.1", forge("Jade-1.20.1-Forge-11.13.3.jar", jade));
        assertEquals("1.20", forge("jade.jar", jade));
    }

    @Test
    @DisplayName("the higher of the loader floor and the forge range counts")
    void highestFloorWins() throws Exception {
        // L_Ender's Cataclysm keeps a forge [36.0.42,) range beside loaderVersion [47,).
        String cataclysm = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\" #mandatory\n[[mods]]\nmodId=\"cataclysm\"\n"
                + "[[dependencies.cataclysm]]\nmodId=\"forge\"\nversionRange=\"[36.0.42,)\"\n";
        assertEquals("1.20.1", forge("L_Enders_Cataclysm-3.31.jar", cataclysm));
        String clothConfig = "modLoader=\"javafml\"\nloaderVersion=\"[34,)\"\n[[mods]]\nmodId=\"cloth_config\"\n"
                + "[[dependencies.cloth_config]]\nmodId=\"forge\"\nversionRange=\"[45,)\"\n";
        assertEquals("1.19.4", forge("cloth-config-11.1.136-forge.jar", clothConfig));
    }

    @Test
    @DisplayName("mod versions, ranges and older names in a file name are not read as its version")
    void fileNameNeedsOneVersionAtOrAboveTheFloor() throws Exception {
        String floor40 = "modLoader=\"javafml\"\nloaderVersion=\"[40,)\"\n[[mods]]\nmodId=\"emi\"\n";
        assertEquals("1.20.1", forge("emi-1.1.24+1.20.1+forge.jar", floor40), "1.1.24 is EMI's own version");
        String floor37 = "modLoader=\"javafml\"\nloaderVersion=\"[37,)\"\n[[mods]]\nmodId=\"imblocker\"\n";
        assertEquals("1.17.1", forge("IMBlocker-5.6.2.1-forge+1.17-1.20.4.jar", floor37),
                "two versions name a supported range, so only the floor is left");
        String floor47 = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"renamed\"\n";
        assertEquals("1.20.1", forge("renamed-1.16.5.jar", floor47), "a name below the floor is stale");
        String noFloor = "modLoader=\"javafml\"\nloaderVersion=\"*\"\n[[mods]]\nmodId=\"universal\"\n";
        assertNull(forge("CustomSkinLoader_Universal-15.1.jar", noFloor));
    }

    @Test
    @DisplayName("a minecraft dependency still decides when the toml has one")
    void minecraftDependencyStillWins() throws Exception {
        String declared = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"declared\"\n"
                + "[[dependencies.declared]]\nmodId=\"minecraft\"\nversionRange=\"[1.20,1.21)\"\n";
        assertEquals("1.20", forge("declared-1.20.1.jar", declared));
    }

    @Test
    @DisplayName("NeoForge jars fall back to the file name, never to FML's loader version")
    void neoForgeFileName() throws Exception {
        String kiwi = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"kiwi\"\n";
        assertEquals("1.21.1", neoForge("Kiwi-1.21.1-NeoForge-15.8.7.jar", kiwi));
        assertNull(neoForge("blossom-blade-1.3.jar", kiwi), "1.3 is a mod version; a toml means 1.13 or newer");
        assertNull(neoForge("kiwi.jar", kiwi), "FML 1 is not a Forge major");
    }

    private String forge(String fileName, String toml) throws Exception {
        return new ModVersionDetector().detectVersion(jar(fileName, "META-INF/mods.toml", toml)).targetMcVersion();
    }

    private String neoForge(String fileName, String toml) throws Exception {
        return new ModVersionDetector().detectVersion(jar(fileName, "META-INF/neoforge.mods.toml", toml)).targetMcVersion();
    }

    private Path jar(String fileName, String entry, String toml) throws Exception {
        Path jar = directory.resolve(fileName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
