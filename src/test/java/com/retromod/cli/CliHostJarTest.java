/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.ClassResourceInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.V21;

/** {@code --host-jar} makes a loader jar's classes, and the --mc-jar's, visible to offline host checks and leaves the other arguments alone. */
class CliHostJarTest {

    private static final String HOST_CLASS = "net/neoforged/neoforge/registries/RegisterEvent";

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        ClassResourceInspector.setOfflineHost(null);
    }

    @Test
    void hostJarsAreReadAndTheirArgumentsRemoved() throws Exception {
        assertFalse(ClassResourceInspector.exists(HOST_CLASS), "the test classpath has no NeoForge classes");
        Path jar = hostJar();

        String[] rest = RetromodCli.applyHostJars(new String[]{
                "batch", "in", "--host-jar", jar.toString(), "--target-loader", "neoforge", "--output", "out"});

        assertArrayEquals(new String[]{"batch", "in", "--target-loader", "neoforge", "--output", "out"}, rest,
                "the command still sees its own arguments in order");
        assertTrue(ClassResourceInspector.exists(HOST_CLASS), "a class in the host jar is now the host's");
        assertTrue(ClassResourceInspector.exists("java/lang/Object"), "JDK classes still resolve");
    }

    @Test
    void theGameJarIsReadFirstWithTheHostJars() throws Exception {
        String vanilla = "net/minecraft/util/TriState";
        Path game = jar("minecraft-client-patched.jar", vanilla);
        Path host = hostJar();

        String[] rest = RetromodCli.applyHostJars(new String[]{
                "batch", "in", "--host-jar", host.toString(), "--mc-jar", game.toString()});

        assertArrayEquals(new String[]{"batch", "in", "--mc-jar", game.toString()}, rest, "--mc-jar stays for the index");
        assertTrue(ClassResourceInspector.exists(vanilla), "the game's own classes are the host's too");
        assertTrue(ClassResourceInspector.exists(HOST_CLASS));
    }

    @Test
    void withoutTheOptionNothingChanges() {
        String[] args = {"transform", "mod.jar", "--output", "out.jar"};
        assertArrayEquals(args, RetromodCli.applyHostJars(args));
        assertFalse(ClassResourceInspector.exists(HOST_CLASS));
    }

    private Path hostJar() throws Exception {
        return jar("neoforge-universal.jar", HOST_CLASS);
    }

    /** A jar holding one empty class. */
    private Path jar(String fileName, String className) throws Exception {
        Path jar = dir.resolve(fileName);
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, className, null, "java/lang/Object", null);
        cw.visitEnd();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(className + ".class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
        return jar;
    }
}
