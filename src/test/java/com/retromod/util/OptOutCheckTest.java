/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptOutCheckTest {

    @TempDir
    Path dir;

    @Test
    void passesFabricRendererThrough() throws IOException {
        Path iris = jar("iris.jar", "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"iris\",\"depends\":{\"sodium\":\"0.6.0\"}}");
        assertTrue(OptOutCheck.isOptedOut(iris), "Iris pins an exact Sodium and must not be converted");
    }

    @Test
    void passesForgeRendererThrough() throws IOException {
        Path embeddium = jar("embeddium.jar", "META-INF/mods.toml",
                "modLoader=\"javafml\"\n[[mods]]\nmodId=\"embeddium\"\nversion=\"0.3.31\"\n");
        assertTrue(OptOutCheck.isOptedOut(embeddium), "Embeddium is a renderer replacement");
    }

    @Test
    void convertsAnAddonThatOnlyNamesARendererAsItsParent() throws IOException {
        Path extra = jar("sodium-extra.jar", "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"sodium-extra\","
                        + "\"custom\":{\"modmenu\":{\"parent\":{\"id\":\"sodium\"}}}}");
        assertFalse(OptOutCheck.isOptedOut(extra), "only the top-level id names the mod");
    }

    @Test
    void convertsAModThatOnlyNamesARendererAsADependency() throws IOException {
        Path mod = jar("shader-aware.jar", "META-INF/mods.toml",
                "modLoader=\"javafml\"\n[[mods]]\nmodId=\"shader_aware\"\n"
                        + "[[dependencies.shader_aware]]\nmodId=\"embeddium\"\ntype=\"incompatible\"\n");
        assertFalse(OptOutCheck.isOptedOut(mod), "a dependency block names another mod");
    }

    @Test
    void convertsAnOrdinaryMod() throws IOException {
        Path mod = jar("plain.jar", "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"plain\"}");
        assertFalse(OptOutCheck.isOptedOut(mod));
    }

    private Path jar(String name, String entry, String text) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(entry));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
