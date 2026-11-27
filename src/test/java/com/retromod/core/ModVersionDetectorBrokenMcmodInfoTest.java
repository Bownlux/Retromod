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
import static org.junit.jupiter.api.Assertions.assertNull;

/** A 1.12.2 mod whose mcmod.info is not valid JSON is still read, as FML 1.12 still loaded it. */
class ModVersionDetectorBrokenMcmodInfoTest {

    @TempDir
    Path directory;

    @Test
    void aStrayQuoteInTheAuthorListLeavesTheIdAndVersionsReadable() throws Exception {
        // Let Me Despawn 1.0.2 for 1.12.2 ships "authorList": ",["frikinjay"]".
        String broken = "[\n{\n  \"modid\": \"letmedespawn\",\n  \"name\": \"Let Me Despawn\",\n"
                + "  \"version\": \"1.12.2-forge-1.0.2\",\n  \"mcversion\": \"1.12.2\",\n"
                + "  \"authorList\": \",[\"frikinjay\"]\",\n  \"dependencies\": []\n}\n]";

        ModVersionInfo info = new ModVersionDetector().detectVersion(jar("letmedespawn-1.12.2-forge-1.0.2.jar", broken));

        assertEquals("letmedespawn", info.modId());
        assertEquals("1.12.2-forge-1.0.2", info.modVersion());
        assertEquals("1.12.2", info.targetMcVersion());
        assertEquals("forge", info.modLoaderType());
    }

    @Test
    void aBrokenFileWithoutAnIdFallsBackToTheModAnnotation() throws Exception {
        // No class carries a 1.12 @Mod either, so there is nothing to translate.
        assertNull(new ModVersionDetector().detectVersion(jar("broken.jar", "[{ \"name\": \"Broken\" ")));
    }

    private Path jar(String fileName, String mcmodInfo) throws Exception {
        Path jar = directory.resolve(fileName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("mcmod.info"));
            out.write(mcmodInfo.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
