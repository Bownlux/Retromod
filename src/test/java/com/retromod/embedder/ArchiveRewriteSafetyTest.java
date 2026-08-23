/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.embedder;

import com.retromod.legacy.EpochTransition;
import com.retromod.legacy.LegacyModAnalysis;
import com.retromod.legacy.LegacyModSupport;
import com.retromod.legacy.ObfuscationDatabase;
import com.retromod.testutil.SignedJarTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveRewriteSafetyTest {

    private static final byte[] PREVIOUS_OUTPUT = "previous-output"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void apiEmbedderBuildsACompleteSiblingJar(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("example.JAR");
        writeJar(source, new String[]{"assets/example/value.txt"},
                new byte[][]{"value".getBytes(StandardCharsets.UTF_8)});
        byte[] original = Files.readAllBytes(source);
        Path output = dir.resolve("example-retromod.jar");
        Files.write(output, PREVIOUS_OUTPUT);

        new ApiEmbedder().embedApisIntoJar(source, Set.of());

        assertArrayEquals(original, Files.readAllBytes(source));
        try (JarFile result = new JarFile(output.toFile())) {
            assertNotNull(result.getJarEntry("assets/example/value.txt"));
            assertNotNull(result.getJarEntry("retromod_embedded/RETROMOD_PROCESSED"));
        }
        assertNoStagingFiles(dir);
    }

    @Test
    void apiEmbedderSanitizesSigningRecordsAfterMutation(@TempDir Path dir) throws Exception {
        Path source = SignedJarTestSupport.createSignedJar(dir, "signed-api.jar",
                SignedJarTestSupport.entries(
                        "assets/example/value.txt", "value".getBytes(StandardCharsets.UTF_8)));

        new ApiEmbedder().embedApisIntoJar(source, Set.of());

        Path output = dir.resolve("signed-api-retromod.jar");
        SignedJarTestSupport.verifyEveryEntry(output);
        assertFalse(SignedJarTestSupport.hasSigningMetadata(output));
        try (JarFile result = new JarFile(output.toFile(), true)) {
            assertNotNull(result.getJarEntry("retromod_embedded/RETROMOD_PROCESSED"));
        }
    }

    @Test
    void apiEmbedderPreservesPreviousOutputWhenAnEntryNameIsRejected(@TempDir Path dir)
            throws Exception {
        Path source = dir.resolve("unsafe.jar");
        writeJar(source, new String[]{"../outside.txt"}, new byte[][]{{1}});
        byte[] original = Files.readAllBytes(source);
        Path output = dir.resolve("unsafe-retromod.jar");
        Files.write(output, PREVIOUS_OUTPUT);

        assertThrows(IOException.class,
                () -> new ApiEmbedder().embedApisIntoJar(source, Set.of()));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output));
        assertNoStagingFiles(dir);
    }

    @Test
    void apiEmbedderPreservesPreviousOutputWhenEntriesAreDuplicated(@TempDir Path dir)
            throws Exception {
        Path source = duplicateEntryJar(dir.resolve("duplicate.jar"));
        byte[] original = Files.readAllBytes(source);
        Path output = dir.resolve("duplicate-retromod.jar");
        Files.write(output, PREVIOUS_OUTPUT);

        assertThrows(IOException.class,
                () -> new ApiEmbedder().embedApisIntoJar(source, Set.of()));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output));
        assertNoStagingFiles(dir);
    }

    @Test
    void legacyTransformBuildsACompleteSiblingJar(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("legacy.JAR");
        writeJar(source, new String[]{"assets/example/value.txt"},
                new byte[][]{"value".getBytes(StandardCharsets.UTF_8)});
        byte[] original = Files.readAllBytes(source);
        Path output = dir.resolve("legacy-retromod-26.2.jar");
        Files.write(output, PREVIOUS_OUTPUT);

        Path result = new LegacyModSupport(dir, "26.2")
                .transformMod(source, transformAnalysis(source));

        assertEquals(output, result);
        assertArrayEquals(original, Files.readAllBytes(source));
        try (JarFile resultJar = new JarFile(output.toFile())) {
            assertNotNull(resultJar.getJarEntry("assets/example/value.txt"));
        }
        assertNoStagingFiles(dir);
    }

    @Test
    void legacyTransformSanitizesSigningRecordsAfterMutation(@TempDir Path dir)
            throws Exception {
        Path source = SignedJarTestSupport.createSignedJar(dir, "signed-legacy.jar",
                SignedJarTestSupport.entries(
                        "assets/example/value.txt", "value".getBytes(StandardCharsets.UTF_8)));

        Path output = new LegacyModSupport(dir, "26.2")
                .transformMod(source, transformAnalysis(source));

        SignedJarTestSupport.verifyEveryEntry(output);
        assertFalse(SignedJarTestSupport.hasSigningMetadata(output));
        assertTrue(Files.exists(output));
    }

    @Test
    void legacyTransformPreservesPreviousOutputWhenAnEntryNameIsRejected(@TempDir Path dir)
            throws Exception {
        Path source = dir.resolve("unsafe.jar");
        writeJar(source, new String[]{"../outside.txt"}, new byte[][]{{1}});
        byte[] original = Files.readAllBytes(source);
        Path output = dir.resolve("unsafe-retromod-26.2.jar");
        Files.write(output, PREVIOUS_OUTPUT);

        LegacyModSupport support = new LegacyModSupport(dir, "26.2");
        assertThrows(IOException.class, () -> support.analyzeMod(source));
        assertThrows(IOException.class,
                () -> support.transformMod(source, transformAnalysis(source)));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output));
        assertNoStagingFiles(dir);
    }

    @Test
    void legacyTransformPreservesPreviousOutputWhenEntriesAreDuplicated(@TempDir Path dir)
            throws Exception {
        Path source = duplicateEntryJar(dir.resolve("duplicate.jar"));
        byte[] original = Files.readAllBytes(source);
        Path output = dir.resolve("duplicate-retromod-26.2.jar");
        Files.write(output, PREVIOUS_OUTPUT);

        LegacyModSupport support = new LegacyModSupport(dir, "26.2");
        assertThrows(IOException.class, () -> support.analyzeMod(source));
        assertThrows(IOException.class,
                () -> support.transformMod(source, transformAnalysis(source)));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output));
        assertNoStagingFiles(dir);
    }

    @Test
    void legacyTransformRejectsUnsafeTargetVersionBeforeWriting(@TempDir Path dir)
            throws Exception {
        Path source = dir.resolve("legacy.jar");
        writeJar(source, new String[]{"assets/example/value.txt"},
                new byte[][]{"value".getBytes(StandardCharsets.UTF_8)});
        byte[] original = Files.readAllBytes(source);

        LegacyModSupport support = new LegacyModSupport(dir, "../../../outside");
        assertThrows(IOException.class,
                () -> support.transformMod(source, transformAnalysis(source)));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertNoStagingFiles(dir);
    }

    private static LegacyModAnalysis transformAnalysis(Path source) {
        LegacyModAnalysis analysis = new LegacyModAnalysis(source);
        analysis.targetMcVersion = "1.20";
        analysis.sourceEpoch = LegacyModSupport.Epoch.DATA_DRIVEN_1_19_TO_1_20;
        analysis.modLoader = LegacyModSupport.ModLoaderType.FABRIC;
        analysis.sourceJavaVersion = 17;
        analysis.classFileVersion = Opcodes.V17;
        analysis.epochTransitions.add(new NoOpTransition());
        return analysis;
    }

    private static Path duplicateEntryJar(Path jar) throws IOException {
        writeJar(jar, new String[]{"one.txt", "two.txt"},
                new byte[][]{{1}, {2}});
        byte[] bytes = Files.readAllBytes(jar);
        byte[] from = "two.txt".getBytes(StandardCharsets.UTF_8);
        byte[] to = "one.txt".getBytes(StandardCharsets.UTF_8);
        int replacements = 0;
        for (int i = 0; i <= bytes.length - from.length; i++) {
            boolean match = true;
            for (int j = 0; j < from.length; j++) {
                if (bytes[i + j] != from[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(to, 0, bytes, i, to.length);
                replacements++;
                i += from.length - 1;
            }
        }
        assertEquals(2, replacements, "entry name must occur in local and central headers");
        Files.write(jar, bytes);
        return jar;
    }

    private static void writeJar(Path jar, String[] names, byte[][] contents) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < names.length; i++) {
                output.putNextEntry(new JarEntry(names[i]));
                output.write(contents[i]);
                output.closeEntry();
            }
        }
    }

    private static void assertNoStagingFiles(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".retromod-")));
        }
    }

    private static final class NoOpTransition implements EpochTransition {
        @Override
        public String name() {
            return "test transition";
        }

        @Override
        public int sourceEpoch() {
            return 5;
        }

        @Override
        public int targetEpoch() {
            return 6;
        }

        @Override
        public ClassVisitor createTransformer(ClassVisitor delegate, ObfuscationDatabase obfDb) {
            return delegate;
        }

        @Override
        public String[] getRequiredShims() {
            return new String[0];
        }

        @Override
        public Map<String, String> getClassRedirects() {
            return Map.of();
        }

        @Override
        public Map<MethodKey, MethodTarget> getMethodRedirects() {
            return Map.of();
        }

        @Override
        public Map<FieldKey, FieldTarget> getFieldRedirects() {
            return Map.of();
        }
    }
}
