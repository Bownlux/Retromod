/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI and AOT compiler refuse a jar whose metadata they cannot read, so a 1.12 mod with an
 * object-form mcmod.info, or none at all, never reached any converter. Both shapes are read now.
 */
class ModVersionDetectorLegacyForgeTest {

    @Test
    @DisplayName("mcmod.info as a bare array")
    void arrayForm(@TempDir Path directory) throws IOException {
        ModVersionInfo info = detect(directory, Map.of("mcmod.info",
                text("[{\"modid\":\"oldmod\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]")));

        assertEquals("oldmod", info.modId());
        assertEquals("1.12.2", info.targetMcVersion());
        assertEquals("forge", info.modLoaderType());
    }

    @Test
    @DisplayName("mcmod.info in the modListVersion 2 object form")
    void objectForm(@TempDir Path directory) throws IOException {
        ModVersionInfo info = detect(directory, Map.of("mcmod.info", text(
                "{\"modListVersion\":2,\"modList\":[{\"modid\":\"abyssal\",\"version\":\"2.0\","
                        + "\"mcversion\":\"1.12.2\"}]}")));

        assertEquals("abyssal", info.modId());
        assertEquals("2.0", info.modVersion());
        assertEquals("1.12.2", info.targetMcVersion());
    }

    @Test
    @DisplayName("No mcmod.info: the 1.12 @Mod annotation names the mod and its versions")
    void annotationOnly(@TempDir Path directory) throws IOException {
        ModVersionInfo info = detect(directory, Map.of("globalxp/GlobalXp.class",
                modClass("modid", "globalxp", "[1.12.2]")));

        assertEquals("globalxp", info.modId());
        assertEquals("1.12.2", info.targetMcVersion());
        assertEquals("forge", info.modLoaderType());
    }

    @Test
    @DisplayName("No mcmod.info and no accepted versions: the mod is read, its version stays unknown")
    void annotationWithoutVersions(@TempDir Path directory) throws IOException {
        ModVersionInfo info = detect(directory, Map.of("globalxp/GlobalXp.class",
                modClass("modid", "globalxp", null)));

        assertEquals("globalxp", info.modId());
        assertNull(info.targetMcVersion(), "an undeclared version is never guessed here");
    }

    @Test
    @DisplayName("A 1.13+ @Mod, which names the mod with value, is not read as a 1.12 mod")
    void modernAnnotationIsNotLegacy(@TempDir Path directory) throws IOException {
        assertNull(detect(directory, Map.of("modern/Main.class", modClass("value", "modern", null))));
    }

    private static ModVersionInfo detect(Path directory, Map<String, byte[]> entries)
            throws IOException {
        Path jar = directory.resolve("mod.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return new ModVersionDetector().detectVersion(jar);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] modClass(String idKey, String modId, String acceptedVersions) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Mod", null, "java/lang/Object", null);
        AnnotationVisitor mod = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        mod.visit(idKey, modId);
        if (acceptedVersions != null) mod.visit("acceptedMinecraftVersions", acceptedVersions);
        mod.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
