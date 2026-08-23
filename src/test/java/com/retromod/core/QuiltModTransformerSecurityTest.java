/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuiltModTransformerSecurityTest {

    private static final String FIXTURE_CLASS = "example/DualMetadataFixture";
    private static final String OLD_TYPE = "example/legacy/OldType";
    private static final String NEW_TYPE = "example/modern/NewType";

    @AfterEach
    void clearTransformerRegistrations() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("native Quilt mods pass through from the real dependency shape")
    void nativeQuiltMetadataPassesThrough(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("native-quilt.jar");
        writeJar(source, metadata("26.1").getBytes(StandardCharsets.UTF_8));
        byte[] original = Files.readAllBytes(source);
        Path outputDirectory = Files.createDirectory(directory.resolve("output"));

        Path output = new QuiltModTransformer("26.1").transformMod(source, outputDirectory);

        assertArrayEquals(original, Files.readAllBytes(output));
    }

    @Test
    @DisplayName("Quilt inputs cannot cross a symbolic-link boundary")
    void rejectsSymbolicLinkInput(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("source.jar");
        writeJar(source, metadata("26.1").getBytes(StandardCharsets.UTF_8));
        Path link = directory.resolve("linked.jar");
        try {
            Files.createSymbolicLink(link, source);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }

        assertFalse(QuiltModTransformer.isQuiltMod(link));
        assertThrows(IOException.class, () -> new QuiltModTransformer("26.1")
            .transformMod(link, Files.createDirectory(directory.resolve("output"))));
    }

    @Test
    @DisplayName("Quilt archives reject entry names that normalize to one output path")
    void rejectsNormalizedDuplicateEntries(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("duplicate-quilt.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(source))) {
            writeEntry(output, "quilt.mod.json",
                metadata("26.1").getBytes(StandardCharsets.UTF_8));
            writeEntry(output, "assets/example.txt", new byte[]{1});
            writeEntry(output, "assets//example.txt", new byte[]{2});
        }
        Path outputDirectory = Files.createDirectory(directory.resolve("output"));

        assertTrue(QuiltModTransformer.isQuiltMod(source));
        assertThrows(IOException.class,
            () -> new QuiltModTransformer("26.1").transformMod(source, outputDirectory));
        assertFalse(Files.exists(outputDirectory.resolve("duplicate-quilt.jar")));
    }

    @Test
    @DisplayName("a Quilt metadata failure cannot publish or replace a partial transform")
    void metadataFailureKeepsExistingOutput(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("old-quilt.jar");
        writeJar(source, metadata("1.20.1").getBytes(StandardCharsets.UTF_8));
        Path outputDirectory = Files.createDirectory(directory.resolve("mods"));
        Path existing = outputDirectory.resolve("old-quilt-retromod.jar");
        writeJar(existing, metadata("previous").getBytes(StandardCharsets.UTF_8));
        byte[] previous = Files.readAllBytes(existing);

        QuiltModTransformer transformer = new QuiltModTransformer("26.1") {
            @Override
            protected void updateQuiltModJson(Path jarPath) throws IOException {
                throw new IOException("injected metadata failure");
            }
        };

        assertThrows(IOException.class,
            () -> transformer.transformMod(source, outputDirectory));
        assertArrayEquals(previous, Files.readAllBytes(existing));
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                .startsWith(".retromod-quilt-output-")));
        }
    }

    @Test
    @DisplayName("Quilt publication refuses a symbolic-link destination")
    void publicationRefusesSymbolicLinkDestination(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("old-quilt.jar");
        writeJar(source, metadata("1.20.1").getBytes(StandardCharsets.UTF_8));
        byte[] sourceBefore = Files.readAllBytes(source);
        Path outputDirectory = Files.createDirectory(directory.resolve("mods"));
        Path outside = directory.resolve("outside.jar");
        writeJar(outside, metadata("previous").getBytes(StandardCharsets.UTF_8));
        byte[] outsideBefore = Files.readAllBytes(outside);
        Path published = outputDirectory.resolve("old-quilt-retromod.jar");
        try {
            Files.createSymbolicLink(published, outside);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }

        assertThrows(IOException.class,
            () -> new QuiltModTransformer("26.1").transformMod(source, outputDirectory));

        assertTrue(Files.isSymbolicLink(published));
        assertArrayEquals(outsideBefore, Files.readAllBytes(outside));
        assertArrayEquals(sourceBefore, Files.readAllBytes(source));
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                .startsWith(".retromod-quilt-output-")));
        }
    }

    @Test
    @DisplayName("Quilt metadata stays authoritative when Fabric metadata also exists")
    void dualMetadataUsesQuiltVersionForClassTransform(@TempDir Path directory)
            throws Exception {
        Path source = directory.resolve("dual-metadata.jar");
        writeDualMetadataJar(source, "1.20.1", "26.1", fixtureClass());
        Path outputDirectory = Files.createDirectory(directory.resolve("output"));

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_TYPE, NEW_TYPE);

        Path output = new QuiltModTransformer("26.1").transformMod(source, outputDirectory);

        assertEquals("dual-metadata-retromod.jar", output.getFileName().toString());
        try (JarFile jar = new JarFile(output.toFile())) {
            JarEntry classEntry = jar.getJarEntry(FIXTURE_CLASS + ".class");
            assertTrue(classEntry != null, "transformed fixture class is missing");
            ClassNode classNode = new ClassNode();
            try (var input = jar.getInputStream(classEntry)) {
                new ClassReader(input.readAllBytes()).accept(classNode, 0);
            }
            assertEquals("L" + NEW_TYPE + ";", classNode.fields.get(0).desc);
        }
    }

    private static void writeJar(Path path, byte[] metadata) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            writeEntry(output, "quilt.mod.json", metadata);
        }
    }

    private static void writeDualMetadataJar(Path path, String quiltMinecraft,
            String fabricMinecraft, byte[] classBytes) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            writeEntry(output, "quilt.mod.json",
                metadata(quiltMinecraft).getBytes(StandardCharsets.UTF_8));
            writeEntry(output, "fabric.mod.json", ("{\"schemaVersion\":1,"
                + "\"id\":\"dual_metadata\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"" + fabricMinecraft + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
            writeEntry(output, FIXTURE_CLASS + ".class", classBytes);
        }
    }

    private static byte[] fixtureClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FIXTURE_CLASS, null,
            "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "L" + OLD_TYPE + ";", null, null)
            .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] content)
            throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static String metadata(String minecraftVersion) {
        return """
            {
              "schema_version": 1,
              "quilt_loader": {
                "group": "retromod.example",
                "id": "example",
                "depends": [
                  {"id": "quilt_loader", "versions": ">=0.20.0"},
                  {"id": "minecraft", "versions": "%s"}
                ]
              }
            }
            """.formatted(minecraftVersion);
    }
}
