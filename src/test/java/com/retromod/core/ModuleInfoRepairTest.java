/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JEI 1.20.1 ships Lucene's descriptor at its root, which exports a Lucene package the jar never
 * contained. Forge 26.2 refused the translated jar with "Invalid module-info".
 */
class ModuleInfoRepairTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("an extracted jar loses a descriptor that exports a package it lacks")
    void dropsAnInvalidDescriptorFromATree() throws Exception {
        Path tree = Files.createDirectories(directory.resolve("tree"));
        Files.write(tree.resolve("module-info.class"), descriptor("org.apache.lucene.core", "org.apache.lucene.search.comparators"));
        Files.createDirectories(tree.resolve("mezz/jei"));
        Files.write(tree.resolve("mezz/jei/Jei.class"), new byte[] {1});

        assertTrue(ModuleInfoRepair.dropInvalid(tree, "jei.jar"));
        assertFalse(Files.exists(tree.resolve("module-info.class")));
        assertTrue(Files.exists(tree.resolve("mezz/jei/Jei.class")), "the mod's own classes stay");
    }

    @Test
    @DisplayName("a descriptor whose packages are all present is kept")
    void keepsAValidDescriptor() throws Exception {
        Path tree = Files.createDirectories(directory.resolve("tree"));
        Files.write(tree.resolve("module-info.class"), descriptor("de.fabmax.physxjni", "de.fabmax.physxjni"));
        Files.createDirectories(tree.resolve("de/fabmax/physxjni"));
        Files.write(tree.resolve("de/fabmax/physxjni/Loader.class"), new byte[] {1});

        assertFalse(ModuleInfoRepair.dropInvalid(tree, "physics-mod.jar"));
        assertTrue(Files.exists(tree.resolve("module-info.class")));
    }

    @Test
    @DisplayName("a jar on disk is rewritten without the invalid descriptor and keeps every other entry")
    void rewritesAJarWithoutTheInvalidDescriptor() throws Exception {
        Path jar = directory.resolve("jei-1.20.1-forge.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            put(out, "module-info.class", descriptor("org.apache.lucene.core", "org.apache.lucene.search.comparators"));
            put(out, "mezz/jei/Jei.class", new byte[] {1});
            // A multi-release copy does not make the root descriptor valid.
            put(out, "META-INF/versions/19/org/apache/lucene/search/comparators/Old.class", new byte[] {2});
        }

        assertTrue(ModuleInfoRepair.dropInvalid(jar));
        assertEquals(List.of("META-INF/MANIFEST.MF", "mezz/jei/Jei.class",
                "META-INF/versions/19/org/apache/lucene/search/comparators/Old.class"), entries(jar));
        assertFalse(ModuleInfoRepair.dropInvalid(jar), "a jar without a descriptor is left alone");
    }

    /** A module-info exporting one package. */
    private static byte[] descriptor(String module, String exportedPackage) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor mv = writer.visitModule(module, 0, null);
        mv.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        mv.visitExport(exportedPackage.replace('.', '/'), 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static List<String> entries(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var entry : Collections.list(zip.entries())) names.add(entry.getName());
        }
        return names;
    }
}
