/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The Fabric runtime reads arrays and JSON escapes in a mod's Minecraft dependency. */
class FabricModTransformerSourceVersionTest {

    private static final String HOST = "26.3";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;

    @BeforeEach
    void registerRedirectForOlderMods() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect("test/Api", "old", "()V", "test/Api", "replacement", "()V");
        transformer.limitRedirectToModsBefore("test/Api", "old", "()V", "1.20.5");
    }

    @AfterEach
    void resetTransformer() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("a mod listing [\"1.21\", \"1.21.1\"] keeps a call meant only for older mods")
    void arrayDependencyGivesTheSourceVersion(@TempDir Path directory) throws Exception {
        Path input = writeFabricMod(directory.resolve("array-mod.jar"), "[\"1.21\", \"1.21.1\"]");

        Path output = new FabricModTransformer(HOST).transformMod(input, gameMods(directory));

        assertEquals("old", firstCallName(output),
                "the mod was built for 1.21, after the redirect's 1.20.5 cutoff");
    }

    @Test
    @DisplayName("a 1.20.1 mod still receives the redirect meant for older mods")
    void olderModStillReceivesTheRedirect(@TempDir Path directory) throws Exception {
        Path input = writeFabricMod(directory.resolve("old-mod.jar"), "\"1.20.1\"");

        Path output = new FabricModTransformer(HOST).transformMod(input, gameMods(directory));

        assertEquals("replacement", firstCallName(output));
    }

    @Test
    @DisplayName("an escaped >=26.3- range on a 26.3 host is copied byte for byte")
    void escapedPrereleaseRangeAtTheHostIsNative(@TempDir Path directory) throws Exception {
        // Gradle escapes '>' and '=' in fabric.mod.json, as native cloth-config builds ship it.
        Path input = writeFabricMod(directory.resolve("native-mod.jar"),
                "\"\\u003e\\u003d26.3-\"");
        Path mods = gameMods(directory);

        Path output = new FabricModTransformer(HOST).transformMod(input, mods);

        assertEquals(mods.resolve("native-mod.jar"), output,
                "a native mod keeps its own file name");
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
        assertFalse(Files.exists(mods.resolve("native-mod-retromod.jar")));
    }

    private static Path gameMods(Path directory) throws Exception {
        return Files.createDirectories(directory.resolve("game/mods"));
    }

    /** A Fabric mod whose {@code depends.minecraft} is the given raw JSON value. */
    private static Path writeFabricMod(Path jar, String minecraftJson) throws Exception {
        String metadata = "{\"schemaVersion\":1,\"id\":\"source_version_test\",\"version\":\"1.0.0\","
                + "\"depends\":{\"fabricloader\":\"*\",\"minecraft\":" + minecraftJson + "}}";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("test/Fixture.class"));
            out.write(callOldApi());
            out.closeEntry();
        }
        return jar;
    }

    /** {@code test/Fixture.probe()}, which calls {@code test/Api.old()}. */
    private static byte[] callOldApi() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Fixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Api", "old", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String firstCallName(Path jarPath) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile());
             InputStream input = jar.getInputStream(jar.getEntry("test/Fixture.class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(node, 0);
            return node.methods.stream()
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .findFirst().orElseThrow().name;
        }
    }
}
