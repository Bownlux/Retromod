/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The discovery pass runs while the loader is still listing mods, in a class loader that
 * must never define a game or loader class (NeoForge 21.9 and newer reject game packages
 * loaded by a parent of the game loader) and never open AWT before the game window.
 */
class BootEngineTest {

    @TempDir
    Path tmp;

    private static BootEngine.EngineClassLoader engine(URL... classPath) {
        return new BootEngine.EngineClassLoader(classPath, BootEngineTest.class.getClassLoader());
    }

    @Test
    @DisplayName("the engine loader refuses game, loader, and AWT classes")
    void refusesGameLoaderAndAwtClasses() throws Exception {
        try (BootEngine.EngineClassLoader loader = engine()) {
            for (String refused : List.of("net.minecraft.world.level.Level",
                    "com.mojang.serialization.Codec", "net.neoforged.fml.loading.FMLLoader",
                    "net.minecraftforge.fml.loading.FMLLoader", "java.awt.Frame",
                    "javax.swing.JOptionPane")) {
                assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(refused, false, loader), refused);
            }
            assertSame(String.class, Class.forName("java.lang.String", false, loader));
            assertSame(org.objectweb.asm.ClassReader.class,
                    Class.forName("org.objectweb.asm.ClassReader", false, loader),
                    "ASM comes from the host so the engine needs no copy of its own");
        }
    }

    /** A runtime jar whose engine reports the context class loader it ran under. */
    private Path stubRuntime() throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/retromod/inplace/DiscoveryEngine",
                null, "java/lang/Object", null);
        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Ljava/util/Map;)Ljava/util/Map;", null, null);
        run.visitCode();
        run.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        run.visitInsn(Opcodes.DUP);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        run.visitVarInsn(Opcodes.ASTORE, 1);
        run.visitVarInsn(Opcodes.ALOAD, 1);
        run.visitLdcInsn("mode");
        run.visitLdcInsn("rewrite");
        run.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        run.visitInsn(Opcodes.POP);
        run.visitVarInsn(Opcodes.ALOAD, 1);
        run.visitLdcInsn("contextLoader");
        run.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false);
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        run.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        run.visitInsn(Opcodes.POP);
        run.visitVarInsn(Opcodes.ALOAD, 1);
        run.visitInsn(Opcodes.ARETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();

        Path jar = tmp.resolve("runtime.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("com/retromod/inplace/DiscoveryEngine.class"));
            zip.write(writer.toByteArray());
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    @DisplayName("the engine runs with its own context class loader, restored afterwards")
    void runsUnderItsOwnContextLoader() throws IOException {
        ClassLoader before = Thread.currentThread().getContextClassLoader();

        Optional<Map<String, Object>> result = BootEngine.run(stubRuntime(),
                Map.of("gameDir", tmp), BootLog.standardOutput());

        assertTrue(result.isPresent());
        assertEquals("rewrite", result.get().get("mode"));
        assertInstanceOf(BootEngine.EngineClassLoader.class, result.get().get("contextLoader"),
                "service lookups in the engine must find Retromod's own providers");
        assertSame(before, Thread.currentThread().getContextClassLoader());
    }

    @Test
    @DisplayName("a runtime without the engine leaves the loader running and reports it")
    void missingEngineIsReportedNotThrown() throws IOException {
        Path empty = tmp.resolve("empty.jar");
        try (JarOutputStream zip = new JarOutputStream(Files.newOutputStream(empty))) {
            zip.putNextEntry(new JarEntry("readme.txt"));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertTrue(BootEngine.run(empty, Map.of(), BootLog.standardOutput()).isEmpty());
    }

    private static URL codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    @Test
    @DisplayName("the real discovery pass runs under the engine loader without game or AWT classes")
    void realEngineRunsUnderTheFilteringLoader() throws Exception {
        Path game = tmp.resolve("game");
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path old = mods.resolve("old.jar");
        try (JarOutputStream zip = new JarOutputStream(Files.newOutputStream(old))) {
            zip.putNextEntry(new JarEntry("META-INF/mods.toml"));
            zip.write(("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                    + "[[mods]]\nmodId=\"oldmod\"\nversion=\"1.0\"\n"
                    + "[[dependencies.oldmod]]\nmodId=\"minecraft\"\nmandatory=true\n"
                    + "versionRange=\"[1.19.2,1.20)\"\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        List<URL> classPath = new ArrayList<>();
        classPath.add(codeSource(com.retromod.inplace.DiscoveryEngine.class));
        classPath.add(codeSource(com.google.gson.Gson.class));
        classPath.add(codeSource(org.slf4j.LoggerFactory.class));

        Map<String, Object> request = BootEngine.request(game, mods, "forge", "1.20.1",
                "47.4.26", InPlaceBoot.FORGE_TOML, "folder");
        Optional<Map<String, Object>> result = BootEngine.run(classPath.toArray(URL[]::new),
                request, BootLog.standardOutput());

        assertTrue(result.isPresent(), "the pass must not need a refused class");
        assertEquals("in-place", result.get().get("mode"));
        assertEquals(List.of(old), BootEngine.paths(result.get(), "hide"));
        assertTrue(result.get().get("statusFile") instanceof Path status && Files.exists(status));
    }
}
