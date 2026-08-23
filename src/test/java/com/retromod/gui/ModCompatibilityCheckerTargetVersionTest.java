/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModCompatibilityCheckerTargetVersionTest {

    private String savedTargetVersion;

    @AfterEach
    void restoreTargetVersion() {
        if (savedTargetVersion != null) {
            RetromodVersion.TARGET_MC_VERSION = savedTargetVersion;
        }
    }

    @Test
    void checkerCapturesTheLoaderDetectedHostVersion(@TempDir Path directory) throws Exception {
        savedTargetVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        ModCompatibilityChecker checker = new ModCompatibilityChecker(directory);

        // A later global change must not make one open GUI prepare a different target.
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        Path mod = fabricMod(directory.resolve("mod-for-26.1.jar"), "26.1");

        ModCompatibilityChecker.IncompatibleMod result = checker.analyzeJar(mod);

        assertNotNull(result, "a 26.1 mod needs preparation for the captured 26.2 host");
        assertEquals("Version: 26.1 to 26.2", result.reason());
    }

    @Test
    void explicitlySelectedUnknownSourceIsPrepared(@TempDir Path directory) throws Exception {
        savedTargetVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1.2";
        ModCompatibilityChecker checker = new ModCompatibilityChecker(directory);
        Path mod = fabricMod(directory.resolve("unknown-source.jar"), null);

        ModCompatibilityChecker.IncompatibleMod result = checker.analyzeJar(mod);

        assertNotNull(result, "an explicit GUI input must not copy an unknown source unchanged");
        assertEquals("Source Minecraft version is not declared", result.reason());
    }

    @Test
    void sameVersionModIsNotFlaggedWithoutApplicableRepairProof(@TempDir Path directory)
            throws Exception {
        savedTargetVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        ModCompatibilityChecker checker = new ModCompatibilityChecker(directory);
        Path mod = fabricMod(directory.resolve("same-version.jar"), "1.20.1");

        ModCompatibilityChecker.IncompatibleMod result = checker.analyzeJar(mod);

        assertNull(result,
            "available API providers alone must not make every native mod look incompatible");
    }

    @Test
    void guiAotLoadsPackagedVersionShims() {
        var shims = ModCompatibilityChecker.loadShimRegistry().getAllShims();

        assertTrue(shims.stream().anyMatch(shim ->
                        "forge".equals(shim.getModLoaderType())
                                && "26.1".equals(shim.getTargetVersion())),
                "the GUI AOT path needs the packaged Forge transition shims");
        assertTrue(shims.stream().anyMatch(shim ->
                        "neoforge".equals(shim.getModLoaderType())
                                && "26.1".equals(shim.getTargetVersion())),
                "the GUI AOT path needs the packaged NeoForge transition shims");
    }

    private static Path fabricMod(Path output, String minecraftVersion) throws Exception {
        String dependency = minecraftVersion == null
                ? ""
                : ",\n    \"depends\": {\"minecraft\": \"" + minecraftVersion + "\"}";
        String metadata = """
                {
                  "schemaVersion": 1,
                  "id": "target_capture_test",
                  "version": "1.0.0"%s
                }
                """.formatted(dependency);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(metadata.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output;
    }
}
