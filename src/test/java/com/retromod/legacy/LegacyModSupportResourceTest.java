/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacy;

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
 * The {@code legacy} command is a transform path of its own, so a 1.12 mod's data has to reach the
 * folders a newer host reads through it as well as through {@code transform}.
 */
class LegacyModSupportResourceTest {

    private static final String RECIPE = "{\"type\":\"minecraft:crafting_shapeless\","
            + "\"ingredients\":[{\"item\":\"minecraft:slime_ball\"}],\"result\":{\"item\":\"slimy:gel\"}}";

    @Test
    @DisplayName("A 1.12 mod's assets-side recipes are copied to data/ by the legacy command")
    void legacyCommandRunsTheResourceMigration(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("slimy-1.0-1.12.2.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(source))) {
            put(jar, "mcmod.info", "[{\"modid\":\"slimy\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]");
            put(jar, "assets/slimy/lang/en_us.json", "{}");
            put(jar, "assets/slimy/recipes/gel.json", RECIPE);
        }
        LegacyModSupport support = new LegacyModSupport(directory, "26.3");

        Path output = support.transformMod(source, support.analyzeMod(source));

        try (JarFile result = new JarFile(output.toFile())) {
            assertNotNull(result.getJarEntry("data/slimy/recipe/gel.json"),
                    "26.3 reads recipes only from data/<ns>/recipe/");
            assertNotNull(result.getJarEntry("assets/slimy/recipes/gel.json"),
                    "the original stays for mod code that reads it from the classpath");
            assertNotNull(result.getJarEntry("mcmod.info"), "the metadata is still written once");
        }
    }

    private static void put(JarOutputStream jar, String name, String content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
