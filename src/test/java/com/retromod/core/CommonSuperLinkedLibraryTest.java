/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.V17;

/**
 * Sophisticated Backpacks returns Sophisticated Core's success or failure result from one branch.
 * Both types live in the library jar, so a join read only from the addon's own classes widened to
 * {@code Object}, and the method failed verification while the item registered.
 */
class CommonSuperLinkedLibraryTest {

    private static final String RESULT = "core/UpgradeSlotChangeResult";
    private static final String SUCCESS = RESULT + "$Success";
    private static final String FAIL = RESULT + "$Fail";

    @Test
    @DisplayName("an addon's join of two library types reads the translated library")
    void joinsThroughTheLibrary(@TempDir Path dir) throws Exception {
        Path library = dir.resolve("core.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library))) {
            put(jar, RESULT, "java/lang/Object");
            put(jar, SUCCESS, RESULT);
            put(jar, FAIL, RESULT);
        }
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(library);
        Function<String, byte[]> addon = name -> null;

        assertEquals(RESULT, RetromodTransformer.resolveCommonSuperClass(SUCCESS, FAIL,
                RetromodTransformer.withLibraries(addon, libraries), name -> null));
        assertEquals("java/lang/Object", RetromodTransformer.resolveCommonSuperClass(SUCCESS, FAIL,
                RetromodTransformer.withLibraries(addon, SyntheticLibraries.NONE), name -> null),
                "without the library the join stays the conservative guess");
    }

    @Test
    @DisplayName("the addon's own class wins over a library class of the same name")
    void ownClassesComeFirst(@TempDir Path dir) throws Exception {
        Path library = dir.resolve("core.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library))) {
            put(jar, SUCCESS, RESULT);
        }
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(library);
        byte[] own = classBytes(SUCCESS, "addon/Base");

        byte[] read = RetromodTransformer.withLibraries(name -> name.equals(SUCCESS) ? own : null, libraries)
                .apply(SUCCESS);
        assertEquals(own, read);
    }

    private static void put(JarOutputStream jar, String name, String superName) throws Exception {
        jar.putNextEntry(new JarEntry(name + ".class"));
        OutputStream out = jar;
        out.write(classBytes(name, superName));
        jar.closeEntry();
    }

    private static byte[] classBytes(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
