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

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dedicated server jar has no client classes, and DataFixerUpper lives in its own library jar.
 * Indexing the server jar used to make every client base and every {@code com/mojang/serialization}
 * base look removed, so a server transform logged "this mod cannot load" for classes that load fine
 * on the client or come from a library (#249, #251 logs).
 */
class SuperclassAbsenceScopeTest {

    @Test
    @DisplayName("absence is proven only inside a package the indexed jar ships")
    void absenceNeedsAShippedPackage(@TempDir Path dir) throws Exception {
        Path serverJar = dir.resolve("server.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(serverJar))) {
            put(jar, "net/minecraft/world/item/Item");
            put(jar, "com/mojang/math/Axis");
        }
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(serverJar);

        assertEquals(SuperclassSafety.Verdict.ABSENT,
                SuperclassSafety.classify("net/minecraft/world/item/RemovedItem", resolver),
                "a missing class in a package the jar ships is really gone");
        assertEquals(SuperclassSafety.Verdict.UNKNOWN,
                SuperclassSafety.classify("net/minecraft/client/gui/screens/Screen", resolver),
                "a server jar says nothing about client classes");
        assertEquals(SuperclassSafety.Verdict.UNKNOWN,
                SuperclassSafety.classify("com/mojang/serialization/MapCodec", resolver),
                "a library class is not expected in the Minecraft jar");
        assertEquals(SuperclassSafety.Verdict.UNKNOWN,
                SuperclassSafety.classify("net/minecraft/world/item/sub/Gone", resolver),
                "a parent package does not vouch for a child package");
    }

    private static void put(JarOutputStream jar, String name) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        jar.putNextEntry(new JarEntry(name + ".class"));
        OutputStream out = jar;
        out.write(cw.toByteArray());
        jar.closeEntry();
    }
}
