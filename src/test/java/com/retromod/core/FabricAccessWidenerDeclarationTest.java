/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mapping.IntermediaryToMojangMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Porting Lib's conditions module widens the private {@code RegistryOps} constructor and subclasses
 * it. Retromod remapped the widener to the official namespace and then dropped its declaration
 * anyway, so the subclass died with {@code IllegalAccessError} while datapacks loaded (#249). A
 * widener whose header already names the runtime namespace has to stay declared.
 */
class FabricAccessWidenerDeclarationTest {

    @Test
    void keepsAWidenerThatWasRemappedToTheHostNamespace(@TempDir Path dir) throws Exception {
        assertTrue(IntermediaryToMojangMapper.getInstance().isLoaded(),
                "the bundled intermediary table is required to remap the widener");
        Path output = transform(dir, "accessWidener v2 intermediary\n"
                + "accessible method net/minecraft/class_6903 <init> "
                + "(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/class_6903$class_7863;)V\n");

        try (JarFile jar = new JarFile(output.toFile())) {
            String metadata = read(jar, "fabric.mod.json");
            assertTrue(metadata.contains("\"accessWidener\""),
                    "a widener in the host namespace must stay declared, got: " + metadata);
            String widener = read(jar, "fixture.accesswidener");
            assertTrue(widener.startsWith("accessWidener v2 official"),
                    "the widener itself must be in the host namespace, got: " + widener);
            assertTrue(widener.contains("net/minecraft/resources/RegistryOps"),
                    "the widened owner must carry its official name, got: " + widener);
        }
    }

    @Test
    void stillDropsAWidenerInAForeignNamespace(@TempDir Path dir) throws Exception {
        Path output = transform(dir,
                "accessWidener v2 named\naccessible class net/minecraft/client/Minecraft\n");

        try (JarFile jar = new JarFile(output.toFile())) {
            String metadata = read(jar, "fabric.mod.json");
            assertFalse(metadata.contains("\"accessWidener\""),
                    "a widener the host would reject must not stay declared, got: " + metadata);
        }
    }

    @Test
    void headerNamespaceIgnoresCommentsAndRejectsPathsOutsideTheMod(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("a.accesswidener"),
                "# comment\n\naccessWidener\tv2\tofficial\n");
        assertEquals("official", FabricModTransformer.tweakerHeaderNamespace(dir, "a.accesswidener"));
        assertEquals("", FabricModTransformer.tweakerHeaderNamespace(dir, "missing.accesswidener"),
                "a missing file has no namespace");
        assertEquals("", FabricModTransformer.tweakerHeaderNamespace(dir, "../a.accesswidener"),
                "a declaration may not name a file outside the mod");
    }

    private static Path transform(Path dir, String widener) throws Exception {
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1.2";
        try {
            Path source = dir.resolve("fixture.jar");
            Files.write(source, jar(
                    "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"fixture\","
                            + "\"version\":\"1.0.0\",\"depends\":{\"fabricloader\":\"*\","
                            + "\"minecraft\":\"1.21.1\"},"
                            + "\"accessWidener\":\"fixture.accesswidener\"}",
                    "fixture.accesswidener", widener));
            Path outputDir = Files.createDirectories(dir.resolve("out"));
            return new FabricModTransformer("26.1.2").transformMod(source, outputDir);
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }
    }

    private static byte[] jar(String... namesAndContents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new JarEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, name + " must be present in the output");
        return new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
    }
}
