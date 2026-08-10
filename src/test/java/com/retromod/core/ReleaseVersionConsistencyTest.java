/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.aot.AotCompiler;
import com.retromod.cli.RetromodCli;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVersionConsistencyTest {

    @Test
    void releaseSurfacesUseTheCanonicalVersion() throws Exception {
        String version = RetromodVersion.RETROMOD_VERSION;

        assertTrue(Files.readString(Path.of("pom.xml"))
                .contains("<version>" + version + "</version>"));
        assertTrue(Files.readString(Path.of("build-all.sh"))
                .contains("VERSION=\"" + version + "\""));
        assertTrue(Files.readString(Path.of("README.md"))
                .contains("Version-" + version.replace("-", "--") + "-blueviolet"));
        assertTrue(Files.readString(Path.of("CHANGELOG.md"))
                .contains("## [" + version + "]"));

        assertEquals(version, privateStaticString(RetromodCli.class, "VERSION"));
        assertEquals(version, privateStaticString(AotCompiler.class, "AOT_VERSION"));
    }

    @Test
    void canonicalVersionIsTheOnlyJavaSourceLiteral() throws Exception {
        String version = RetromodVersion.RETROMOD_VERSION;
        long filesWithLiteral;
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            filesWithLiteral = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, version))
                    .count();
        }
        assertEquals(1, filesWithLiteral,
                "runtime versions must reference RetromodVersion instead of copying its literal");
    }

    private static String privateStaticString(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static boolean contains(Path path, String value) {
        try {
            return Files.readString(path).contains(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not inspect " + path, e);
        }
    }
}
