/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** #366: a removed registry key must not be matched to a similarly named one, as BLOCK_TYPE was to BLOCK. */
class FuzzyIdentityFieldTest {

    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";

    @TempDir
    Path dir;

    @Test
    @DisplayName("A missing registry key stays missing instead of becoming its neighbor")
    void registryKeysAreNotGuessed() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        assertNull(resolver.resolveField(REGISTRIES, "BLOCK_TYPE", KEY), "BLOCK_TYPE must not become BLOCK");
    }

    @Test
    @DisplayName("Other renamed constants are still matched")
    void otherConstantsStillResolve() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        FuzzyMethodResolver.FieldInfo match = resolver.resolveField(ATTRIBUTES, "GENERIC_MAX_HEALTH", HOLDER);
        assertNotNull(match, "a constant of an ordinary type keeps its fuzzy match");
        assertEquals("MAX_HEALTH", match.name());
    }

    private Path hostJar() throws Exception {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            write(out, REGISTRIES, constants(REGISTRIES, KEY, "BLOCK", "ITEM"));
            write(out, ATTRIBUTES, constants(ATTRIBUTES, HOLDER, "MAX_HEALTH", "ARMOR"));
        }
        return jar;
    }

    private static void write(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] constants(String owner, String descriptor, String... names) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        for (String name : names) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name, descriptor, null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
