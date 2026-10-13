/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** #330: MCreator Forge jars that also carry a stale neoforge.mods.toml claiming the host version. */
class ForgeTomlChoiceTest {

    private static final String STALE_NEO = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"brainrot\"\n"
            + "[[dependencies.brainrot]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.1]\"\n";
    private static final String FORGE = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"brainrot\"\n"
            + "[[dependencies.brainrot]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.1,1.21)\"\n";

    @TempDir
    Path dir;

    @Test
    void forgeCodeReadsTheForgeMetadata() throws IOException {
        Path jar = jar("Lnet/minecraftforge/fml/common/Mod;");
        try (JarFile file = new JarFile(jar.toFile())) {
            assertEquals(ForgeTomlChoice.FORGE_TOML, ForgeTomlChoice.metadataEntry(file));
        }
        assertEquals("1.20.1", new ModVersionDetector().detectVersion(jar).targetMcVersion(),
                "the mod is converted from 1.20.1, not left as a 1.21.1 mod");
    }

    @Test
    void neoForgeCodeKeepsTheNeoForgeMetadata() throws IOException {
        try (JarFile file = new JarFile(jar("Lnet/neoforged/fml/common/Mod;").toFile())) {
            assertEquals(ForgeTomlChoice.NEOFORGE_TOML, ForgeTomlChoice.metadataEntry(file));
        }
    }

    private Path jar(String modAnnotation) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "brainrot/Brainrot", null, "java/lang/Object", null);
        cw.visitAnnotation(modAnnotation, true).visitEnd();
        cw.visitEnd();
        Path jar = dir.resolve(modAnnotation.contains("neoforged") ? "neo.jar" : "forge.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            put(out, "META-INF/neoforge.mods.toml", STALE_NEO.getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/mods.toml", FORGE.getBytes(StandardCharsets.UTF_8));
            put(out, "brainrot/Brainrot.class", cw.toByteArray());
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
