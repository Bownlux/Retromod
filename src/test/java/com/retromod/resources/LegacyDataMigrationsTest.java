/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI, AOT and nested-jar paths write a finished jar instead of an extracted tree, and used to
 * skip the pre-1.20 structure and smithing migrations that the runtime Forge path runs.
 */
class LegacyDataMigrationsTest {

    private static final String RECIPE = "data/examplemod/recipes/netherite_dagger.json";
    private static final String OLD_RECIPE = """
            {"type": "minecraft:smithing",
             "base": {"item": "examplemod:diamond_dagger"},
             "addition": {"item": "minecraft:netherite_ingot"},
             "result": {"item": "examplemod:netherite_dagger"}}
            """;
    private static final String MODS_TOML = """
            modLoader="javafml"
            loaderVersion="[43,)"
            license="MIT"
            [[mods]]
            modId="examplelib"
            version="1.0.0"
            [[dependencies.examplelib]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.19.2,1.20)"
            ordering="NONE"
            side="BOTH"
            """;

    private static byte[] jar(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, String> read(byte[] jar) throws Exception {
        Map<String, String> entries = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static String type(String recipeJson) {
        return JsonParser.parseString(recipeJson).getAsJsonObject().get("type").getAsString();
    }

    @Test
    @DisplayName("a finished 1.19.2 jar gets its smithing recipes converted, other entries kept")
    void finishedJarIsMigrated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mod.jar");
        Files.write(file, jar(Map.of(RECIPE, OLD_RECIPE, "examplemod/Mod.class", "class bytes")));

        assertEquals(1, LegacyDataMigrations.migrateArchive(file, "1.19.2", "1.20.1"));

        Map<String, String> entries = read(Files.readAllBytes(file));
        assertEquals("minecraft:smithing_transform", type(entries.get(RECIPE)));
        assertEquals("class bytes", entries.get("examplemod/Mod.class"), "non-data entries are copied as is");
        try (ZipFile ignored = new ZipFile(file.toFile())) {
            // The rewritten jar has a valid central directory.
        }
    }

    @Test
    @DisplayName("a nested jar is judged by its own mods.toml")
    void nestedJarReadsItsOwnVersion() throws Exception {
        byte[] nested = jar(Map.of("META-INF/mods.toml", MODS_TOML, RECIPE, OLD_RECIPE));

        byte[] migrated = LegacyDataMigrations.migrateArchive(nested, "1.20.1");

        assertNotSame(nested, migrated);
        assertEquals("minecraft:smithing_transform", type(read(migrated).get(RECIPE)));
        assertEquals(MODS_TOML, read(migrated).get("META-INF/mods.toml"));
    }

    @Test
    @DisplayName("a mod built for the host, or an unknown version, passes through untouched")
    void newerOrUnknownJarsAreUntouched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mod.jar");
        byte[] original = jar(Map.of(RECIPE, OLD_RECIPE));
        Files.write(file, original);

        assertEquals(0, LegacyDataMigrations.migrateArchive(file, "1.20.1", "1.20.1"));
        assertEquals(0, LegacyDataMigrations.migrateArchive(file, null, "1.20.1"),
                "a jar with no readable metadata is not guessed at");
        assertEquals(0, LegacyDataMigrations.migrateArchive(file, "${minecraft_version}", "1.20.1"),
                "an unexpanded placeholder is not read as an old version");
        assertArrayEquals(original, Files.readAllBytes(file));
        assertSame(original, LegacyDataMigrations.migrateArchive(original, "1.20.1"));
    }
}
