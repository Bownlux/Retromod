/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code mods.toml} to {@code neoforge.mods.toml} promotion that lets modern
 * NeoForge accept 1.20.1 (Neo)Forge mods (#42). The file rename and host gate need a
 * NeoForge runtime; the {@code loaderVersion} relax is pure and testable here.
 */
class ForgeModTransformerTest {

    @Test
    @DisplayName("refmap discovery accepts JSON resources but not nested jar filenames")
    void refmapDiscoveryRequiresJson() {
        assertTrue(ForgeModTransformer.isRefmapResource("mappings/compat-refmap.json"));
        assertTrue(ForgeModTransformer.isRefmapResource("MAPPINGS/COMPAT-REFMAP.JSON"));
        assertFalse(ForgeModTransformer.isRefmapResource(
                "META-INF/jarjar/refmap-library.jar"));
    }

    @Test
    @DisplayName("#42: top-level loaderVersion is relaxed to [1,)")
    void relaxesLoaderVersion() {
        String in = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n";
        String out = ForgeModTransformer.relaxLoaderVersion(in);
        assertTrue(out.contains("loaderVersion=\"[1,)\""), out);
        assertFalse(out.contains("[47,)"), "old Forge loaderVersion must be gone");
        assertTrue(out.contains("modLoader=\"javafml\""), "other fields untouched");
        assertTrue(out.contains("license=\"MIT\""), "other fields untouched");
    }

    @Test
    @DisplayName("#42: spaced form works and dependency versionRanges are left alone")
    void spacedFormAndDependenciesUntouched() {
        String in = "loaderVersion = \"[2,)\"\n"
                + "[[dependencies.foo]]\n"
                + "modId=\"minecraft\"\n"
                + "versionRange=\"[1.20.1]\"\n";
        String out = ForgeModTransformer.relaxLoaderVersion(in);
        assertTrue(out.contains("loaderVersion = \"[1,)\""), out);
        // only the top-level loaderVersion changes, not deps
        assertTrue(out.contains("versionRange=\"[1.20.1]\""), "dependency versionRange must be untouched");
    }

    @Test
    @DisplayName("No loaderVersion line → content unchanged")
    void noLoaderVersionIsNoop() {
        String in = "modLoader=\"javafml\"\nlicense=\"MIT\"\n";
        assertEquals(in, ForgeModTransformer.relaxLoaderVersion(in));
    }

    @Test
    @DisplayName("#42: a `forge` dependency is repointed at `neoforge` (which exists on NeoForge)")
    void pointsForgeDepAtNeoForge() {
        // a forge dep plus a minecraft dep
        String in = "[[dependencies.twigs]]\n"
                + "modId=\"forge\"\n"
                + "versionRange=\"[0,)\"\n"
                + "[[dependencies.twigs]]\n"
                + "modId = \"minecraft\"\n"
                + "versionRange=\"[1.21.1,)\"\n";
        String out = ForgeModTransformer.pointForgeDependencyAtNeoForge(in);
        assertTrue(out.contains("modId=\"neoforge\""), "forge dep must be repointed at neoforge: " + out);
        assertFalse(out.contains("modId=\"forge\""), "no `forge` dependency should remain");
        // the minecraft dependency and its versionRange stay untouched
        assertTrue(out.contains("modId = \"minecraft\""), "minecraft dep untouched");
        assertTrue(out.contains("versionRange=\"[1.21.1,)\""), "minecraft versionRange untouched");
    }

    @Test
    @DisplayName("#62: a missing license is added as a root-table key (before [[mods]])")
    void addsMissingLicense() {
        String in = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n\n[[mods]]\nmodId=\"survivalisland\"\n";
        String out = ForgeModTransformer.ensureLicense(in);
        assertTrue(out.contains("license="), "license must be added: " + out);
        // must stay in the root table, before the [[mods]] header
        assertTrue(out.indexOf("license=") < out.indexOf("[[mods]]"),
                "license must precede the [[mods]] table: " + out);
        assertTrue(out.contains("modId=\"survivalisland\""), "existing content preserved");
    }

    @Test
    @DisplayName("#62: an existing license is left untouched")
    void keepsExistingLicense() {
        String in = "modLoader=\"javafml\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"x\"\n";
        assertEquals(in, ForgeModTransformer.ensureLicense(in),
                "must not modify a toml that already declares a license");
    }

    /**
     * NeoForge/Forge runtime path: {@link ForgeModTransformer#transformMod} must strip {@code //}
     * comments from a mod's bundled worldgen JSON, since 26.1's strict gson rejects them
     * (FatalStartupException on Philips Ruins). Checks the {@code migrateTree} wiring is reached
     * by the runtime path, not just the offline CLI (#42).
     */
    @Test
    @DisplayName("runtime transformMod strips lenient-JSON comments from bundled worldgen data (26.x)")
    void transformModMigratesBundledWorldgenData(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("structuremod-1.21.1.jar");
        String commented = "{\n  // a comment 26.1 strict gson rejects\n  \"processors\": []\n}";
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(src))) {
            writeEntry(jos, "META-INF/neoforge.mods.toml",
                    "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                  + "[[mods]]\nmodId=\"smod\"\n"
                  + "[[dependencies.smod]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.1,)\"\n");
            writeEntry(jos, "data/smod/worldgen/processor_list/x.json", commented);
        }

        Path outDir = Files.createDirectory(tmp.resolve("out"));
        Path out = new ForgeModTransformer("26.1").transformMod(src, outDir);

        assertNotNull(out, "transformMod should produce an output jar");
        String migrated = readEntry(out, "data/smod/worldgen/processor_list/x.json");
        assertNotNull(migrated, "the bundled data file must survive the transform");
        assertFalse(migrated.contains("//"), "the // comment must be stripped by the runtime path: " + migrated);
        // and it must now parse under a strict (26.1-style) reader
        var r = new com.google.gson.stream.JsonReader(new java.io.StringReader(migrated));
        r.setLenient(false);
        assertDoesNotThrow(() -> com.google.gson.JsonParser.parseReader(r),
                "migrated worldgen JSON must be strict-parseable");
    }

    private static void writeEntry(JarOutputStream jos, String name, String content) throws Exception {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
    }

    private static String readEntry(Path jar, String name) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry e = jf.getJarEntry(name);
            if (e == null) return null;
            try (var is = jf.getInputStream(e)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    @DisplayName("#62: license is added even when there is no table header")
    void addsLicenseWithNoTableHeader() {
        String in = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n";
        String out = ForgeModTransformer.ensureLicense(in);
        assertTrue(out.contains("license="), "license must be added even with no [table]: " + out);
    }
}
