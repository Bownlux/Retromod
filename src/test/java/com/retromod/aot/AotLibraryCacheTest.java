/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticLibraries;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An addon compiled with its library reads the library's classes for its frames, so its AOT cache
 * entry is good only for a compile with the same library files. A jar that uses no library keeps
 * hitting the cache whatever libraries a batch passes.
 */
class AotLibraryCacheTest {

    private static final String HOST = "26.2";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an entry compiled with a library is served only to a compile with the same library file")
    void libraryLinkedEntriesAreNotCrossServed(@TempDir Path dir) throws Exception {
        Path in = inputs(dir);
        AotCompiler compiler = compiler(dir);
        Path library = translatedLibrary(compiler, in, dir);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(library);

        String linked = compiledAt(compiler.compileModAot(in.resolve("addon.jar"), true, libraries));
        assertTrue(context(dir, "addon").contains("|libraries=corelib.jar="), context(dir, "addon"));
        String plain = compiledAt(compiler.compileModAot(in.resolve("addon.jar"), true));
        assertNotEquals(linked, plain, "a compile without the library must not take the library-linked entry");
        assertFalse(context(dir, "addon").contains("|libraries="));
        String relinked = compiledAt(compiler.compileModAot(in.resolve("addon.jar"), true, libraries));
        assertNotEquals(plain, relinked, "a compile with the library must not take the plain entry");
        assertEquals(relinked, compiledAt(compiler.compileModAot(in.resolve("addon.jar"), true, libraries)),
                "the same library file serves the entry again");

        // A changed library file is a different translation for the addon.
        write(library, metadata("corelib", null), Map.of("corelib/Result", type("corelib/Result", "java/lang/Object"),
                "corelib/Extra", type("corelib/Extra", "java/lang/Object")));
        SyntheticLibraries changed = SyntheticLibraries.create();
        changed.add(library);
        assertNotEquals(relinked, compiledAt(compiler.compileModAot(in.resolve("addon.jar"), true, changed)));
    }

    @Test
    @DisplayName("a jar that uses no library hits the cache whatever libraries are passed")
    void unlinkedJarsKeepTheirCacheEntry(@TempDir Path dir) throws Exception {
        Path in = inputs(dir);
        AotCompiler compiler = compiler(dir);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(translatedLibrary(compiler, in, dir));

        String first = compiledAt(compiler.compileModAot(in.resolve("zother.jar"), true, libraries));
        assertEquals(first, compiledAt(compiler.compileModAot(in.resolve("zother.jar"), true, libraries)));
        assertEquals(first, compiledAt(compiler.compileModAot(in.resolve("zother.jar"), true)),
                "the entry names no library, so a compile without libraries takes it too");
    }

    private static AotCompiler compiler(Path dir) throws Exception {
        return AotCompiler.forOfflineInputs(new ShimRegistry(), HOST, dir.toRealPath().resolve("cache"));
    }

    /** The library compiled first and copied out, as a batch keeps its output for the jars after it. */
    private static Path translatedLibrary(AotCompiler compiler, Path in, Path dir) throws Exception {
        Path compiled = compiler.compileModAot(in.resolve("corelib.jar"), true);
        Path out = Files.createDirectories(dir.resolve("out")).resolve("corelib.jar");
        return Files.copy(compiled, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The compile time stamped in a jar's manifest, with a pause so the next compile differs. */
    private static String compiledAt(Path jar) throws Exception {
        Thread.sleep(5);
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest().getMainAttributes().getValue("Retromod-Compiled-Time");
        }
    }

    private static String context(Path dir, String name) throws Exception {
        Path cached = dir.toRealPath().resolve("cache").resolve(name + "-aot.jar");
        try (JarFile file = new JarFile(cached.toFile())) {
            return file.getManifest().getMainAttributes().getValue("Retromod-AOT-Context");
        }
    }

    /** A library with a base and two kinds, an addon that uses them, and a mod that uses neither. */
    private static Path inputs(Path dir) throws Exception {
        Path in = Files.createDirectories(dir.resolve("in"));
        write(in.resolve("corelib.jar"), metadata("corelib", null), Map.of(
                "corelib/Result", type("corelib/Result", "java/lang/Object"),
                "corelib/Success", type("corelib/Success", "corelib/Result"),
                "corelib/Failure", type("corelib/Failure", "corelib/Result")));
        write(in.resolve("addon.jar"), metadata("addon", "corelib"),
                Map.of("addon/Kind", type("addon/Kind", "corelib/Success")));
        write(in.resolve("zother.jar"), metadata("zother", null),
                Map.of("zother/Thing", type("zother/Thing", "java/lang/Object")));
        return in;
    }

    private static Map.Entry<String, byte[]> metadata(String id, String dependency) {
        return Map.entry("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"" + (dependency == null ? "" : ",\"" + dependency + "\":\"*\"")
                + "}}").getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path jar, Map.Entry<String, byte[]> metadata, Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(metadata.getKey(), metadata.getValue());
        classes.forEach((name, bytes) -> entries.put(name + ".class", bytes));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static byte[] type(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
