/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignatureVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void codeSourcePathDecodesSpaces() throws Exception {
        Path jar = createJarUnderSpacedDirectory();

        assertEquals(jar, SignatureVerifier.jarPathFromCodeSource(jar.toUri().toURL()));
    }

    @Test
    void nestedJarUrlResolvesOuterJar() throws Exception {
        Path jar = createJarUnderSpacedDirectory();
        URL nested = new URL("jar:" + jar.toUri() + "!/com/retromod/security/");

        assertEquals(jar, SignatureVerifier.jarPathFromCodeSource(nested));
    }

    @Test
    void classDirectoryAndNonFileUrlAreNotReleaseJars() throws Exception {
        assertNull(SignatureVerifier.jarPathFromCodeSource(tempDir.toUri().toURL()));
        assertNull(SignatureVerifier.jarPathFromCodeSource(
                new URL("https://example.invalid/retromod.jar")));
    }

    @Test
    void hashCoversProviderClassesOutsideRetromodPackageAndServiceDescriptors() throws Exception {
        Map<String, byte[]> baseline = new LinkedHashMap<>();
        baseline.put("com/retromod/core/Main.class", new byte[]{1});
        baseline.put("outside/Provider.class", new byte[]{2});
        baseline.put("META-INF/services/com.retromod.core.VersionShim",
                "outside.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String original = computeHash(createJar("original.jar", baseline));

        Map<String, byte[]> changedClass = new LinkedHashMap<>(baseline);
        changedClass.put("outside/Provider.class", new byte[]{3});
        Map<String, byte[]> changedService = new LinkedHashMap<>(baseline);
        changedService.put("META-INF/services/com.retromod.core.VersionShim",
                "outside.OtherProvider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertNotEquals(original, computeHash(createJar("changed-class.jar", changedClass)));
        assertNotEquals(original, computeHash(createJar("changed-service.jar", changedService)));
    }

    @Test
    void loaderVariantClassesDoNotChangeSharedReleaseHash() throws Exception {
        Map<String, byte[]> baseline = new LinkedHashMap<>();
        baseline.put("com/retromod/core/Main.class", new byte[]{1});
        String original = computeHash(createJar("baseline.jar", baseline));

        Map<String, byte[]> loaderVariant = new LinkedHashMap<>(baseline);
        loaderVariant.put("org/objectweb/asm/ClassReader.class", new byte[]{2});
        loaderVariant.put("javax/annotation/Nullable.class", new byte[]{3});

        assertEquals(original, computeHash(createJar("loader-variant.jar", loaderVariant)));
    }

    @Test
    void archiveEntryNamesAndBodiesAreLengthFramed() throws Exception {
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("META-INF/services/a", new byte[] {'b'});
        Map<String, byte[]> second = new LinkedHashMap<>();
        second.put("META-INF/services/ab", new byte[0]);

        assertNotEquals(computeHash(createJar("framed-a.jar", first)),
                computeHash(createJar("framed-b.jar", second)));
    }

    @Test
    void manifestReadRejectsOversizedInput() throws Exception {
        Path jarPath = tempDir.resolve("large-manifest.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            output.write(new byte[1024 * 1024 + 1]);
            output.closeEntry();
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertThrows(java.io.IOException.class,
                    () -> SignatureVerifier.readBoundedManifest(jar));
        }
    }

    @Test
    void manifestReadRejectsNormalizedDuplicateEntries() throws Exception {
        Path jarPath = tempDir.resolve("duplicate-manifest.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\n\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new java.util.zip.ZipEntry("META-INF/./MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\n\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertThrows(java.io.IOException.class,
                    () -> SignatureVerifier.readBoundedManifest(jar));
        }
    }

    private String computeHash(Path path) throws Exception {
        try (JarFile jar = new JarFile(path.toFile())) {
            return SignatureVerifier.computeSelfHash(jar);
        }
    }

    private Path createJar(String name, Map<String, byte[]> entries) throws Exception {
        Path path = tempDir.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    private Path createJarUnderSpacedDirectory() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("Application Support"));
        return Files.createFile(directory.resolve("retromod.jar"));
    }
}
