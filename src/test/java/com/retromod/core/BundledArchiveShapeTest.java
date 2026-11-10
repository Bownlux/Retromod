/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jar shapes real mods ship that every translation path has to accept. */
class BundledArchiveShapeTest {

    private static final String FABRIC_METADATA = "{\"schemaVersion\":1,\"id\":\"fixture\",\"version\":\"1.0.0\","
            + "\"depends\":{\"fabricloader\":\"*\",\"minecraft\":\"1.21.1\"}%s}";
    private static final String FORGE_METADATA = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
            + "[[mods]]\nmodId=\"fixture\"\nversion=\"1.0\"\n"
            + "[[dependencies.fixture]]\nmodId=\"minecraft\"\ntype=\"required\"\nversionRange=\"[1.21.1]\"\n";

    @Test
    void fabricRuntimeAcceptsABundledJarThatRepeatsAFolder(@TempDir Path dir) throws Exception {
        Path source = write(dir, "fixture.jar", fabricMod("META-INF/jars/app.jar", repeatedFolderJar()));

        Path result = new FabricModTransformer("26.3").transformMod(source, Files.createDirectories(dir.resolve("out")));

        assertNotNull(result, "a repeated folder entry carries no content, so the mod must still translate");
        assertBundledLibraryKept(result, "META-INF/jars/app.jar");
    }

    @Test
    void forgeRuntimeAcceptsABundledJarThatRepeatsAFolder(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", FORGE_METADATA.getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/jarjar/app.jar", repeatedFolderJar());
        Path source = write(dir, "fixture.jar", jar(entries));

        Path result = new ForgeModTransformer("26.3").transformMod(source, Files.createDirectories(dir.resolve("out")));

        assertNotNull(result, "CrashAssistant bundles a jar that repeats META-INF/versions/");
        assertBundledLibraryKept(result, "META-INF/jarjar/app.jar");
    }

    @Test
    void cliAcceptsABundledJarThatRepeatsAFolder(@TempDir Path dir) throws Exception {
        Path source = write(dir, "fixture.jar", fabricMod("META-INF/jars/app.jar", repeatedFolderJar()));
        Path output = dir.resolve("fixture-out.jar");

        CliHarness.transform(source, output, "26.3");

        assertBundledLibraryKept(output, "META-INF/jars/app.jar");
    }

    @Test
    void outerReadersAcceptAModJarThatRepeatsAFolder(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerSyntheticClass(SYNTHETIC, emptyClass(SYNTHETIC, "java/lang/Object"));
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("fabric.mod.json", String.format(FABRIC_METADATA, "").getBytes(StandardCharsets.UTF_8));
            entries.put("mod/UsesSynthetic.class", emptyClass("mod/UsesSynthetic", SYNTHETIC));
            Path mod = write(dir, "fat.jar", withRepeatedFolder(entries));

            assertNotNull(new ModVersionDetector().detectVersion(mod), "the version detector reads a merged fat jar");
            try (JarFile jar = new JarFile(mod.toFile())) {
                assertEquals(java.util.List.of("mod/UsesSynthetic.class"),
                        HybridTransformationEngine.collectClassEntryNames(jar, 100), "the hybrid engine lists its classes");
            }
            assertTrue(SyntheticEmbedder.embedIntoJar(mod, "fat", transformer) > 0,
                    "the embedder adds the compatibility class the mod names");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    void everyTranslationPathAcceptsAModJarThatRepeatsAFolder(@TempDir Path dir) throws Exception {
        Map<String, byte[]> fabricEntries = new LinkedHashMap<>();
        fabricEntries.put("fabric.mod.json", String.format(FABRIC_METADATA, "").getBytes(StandardCharsets.UTF_8));
        fabricEntries.put("lib/Library.class", libraryClass());
        Path fabric = write(dir, "fabric-fat.jar", withRepeatedFolder(fabricEntries));
        Map<String, byte[]> forgeEntries = new LinkedHashMap<>();
        forgeEntries.put("META-INF/mods.toml", FORGE_METADATA.getBytes(StandardCharsets.UTF_8));
        forgeEntries.put("lib/Library.class", libraryClass());
        Path forge = write(dir, "forge-fat.jar", withRepeatedFolder(forgeEntries));

        assertNotNull(new FabricModTransformer("26.3").transformMod(fabric, Files.createDirectories(dir.resolve("fabric-out"))),
                "the Fabric runtime");
        assertNotNull(new ForgeModTransformer("26.3").transformMod(forge, Files.createDirectories(dir.resolve("forge-out"))),
                "the Forge runtime");
        Path cliOutput = dir.resolve("cli-out.jar");
        CliHarness.transform(forge, cliOutput, "26.3");
        assertTrue(Files.isRegularFile(cliOutput), "the CLI");
        Path aotOutput = dir.resolve("aot-out.jar");
        java.lang.reflect.Method compile = com.retromod.aot.AotCompiler.class.getDeclaredMethod("compileJar",
                Path.class, Path.class, com.retromod.embedder.ModVersionInfo.class);
        compile.setAccessible(true);
        compile.invoke(new com.retromod.aot.AotCompiler(new com.retromod.shim.ShimRegistry(), "26.3"), fabric, aotOutput,
                new ModVersionDetector().detectVersion(fabric));
        assertTrue(Files.isRegularFile(aotOutput), "AOT");
    }

    @Test
    void cliAcceptsAnEntryLargerThanFiftyMegabytes(@TempDir Path dir) throws Exception {
        // Essential 1.5.1 carries its 54 MB game jar beside its loader.
        byte[] large = new byte[51 * 1024 * 1024];
        new java.util.Random(7).nextBytes(large);
        Path source = write(dir, "fixture.jar", fabricMod("essential-inner.bin", large));
        Path output = dir.resolve("fixture-out.jar");

        CliHarness.transform(source, output, "26.3");

        try (JarFile jar = new JarFile(output.toFile())) {
            JarEntry entry = jar.getJarEntry("essential-inner.bin");
            assertNotNull(entry, "the large entry must be written");
            assertEquals(large.length, entry.getSize());
        }
    }

    @Test
    void cliRemapsAWidenerDeclaredUnderAnyName(@TempDir Path dir) throws Exception {
        // Lithostitched, Controlify, Tectonic and Yet Another Config Lib name theirs .ct.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", String.format(FABRIC_METADATA, ",\"accessWidener\":\"fixture.ct\"")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("fixture.ct", ("accessWidener v2 intermediary\n"
                + "accessible class net/minecraft/class_6903\n").getBytes(StandardCharsets.UTF_8));
        Path source = write(dir, "fixture.jar", jar(entries));
        Path output = dir.resolve("fixture-out.jar");

        CliHarness.transform(source, output, "26.3");

        try (JarFile jar = new JarFile(output.toFile())) {
            String widener = new String(jar.getInputStream(jar.getJarEntry("fixture.ct")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(widener.startsWith("accessWidener v2 official"),
                    "Fabric rejects an intermediary widener on a 26.x host before any mod runs, got: " + widener);
            assertTrue(widener.contains("net/minecraft/resources/RegistryOps"), widener);
        }
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** A library jar whose META-INF/versions/ folder entry appears twice, as in a merged fat jar. */
    private static byte[] repeatedFolderJar() throws Exception {
        return withRepeatedFolder(Map.of("lib/Library.class", libraryClass()));
    }

    /** A jar of {@code entries} whose META-INF/versions/ folder entry appears twice. */
    private static byte[] withRepeatedFolder(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("META-INF/versions/"));
            out.closeEntry();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
            // Written under a placeholder of the same length, because JarOutputStream refuses a repeat.
            out.putNextEntry(new JarEntry("META-INF/versionz/"));
            out.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        byte[] placeholder = "META-INF/versionz/".getBytes(StandardCharsets.US_ASCII);
        byte[] repeated = "META-INF/versions/".getBytes(StandardCharsets.US_ASCII);
        int replaced = 0;
        for (int i = 0; i + placeholder.length <= archive.length; i++) {
            if (java.util.Arrays.equals(archive, i, i + placeholder.length, placeholder, 0, placeholder.length)) {
                System.arraycopy(repeated, 0, archive, i, repeated.length);
                replaced++;
            }
        }
        assertEquals(2, replaced, "the local and central headers both name the folder");
        return archive;
    }

    private static final String SYNTHETIC = "com/retromod/generated/FatJarProbe";

    private static byte[] emptyClass(String name, String superName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] libraryClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "lib/Library", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fabricMod(String extraName, byte[] extra) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", String.format(FABRIC_METADATA, "").getBytes(StandardCharsets.UTF_8));
        entries.put(extraName, extra);
        return jar(entries);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                // A bundled jar is stored as it is, so its size is its real size.
                if (entry.getValue().length > 1024 * 1024) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.getValue());
                    jarEntry.setMethod(ZipEntry.STORED);
                    jarEntry.setSize(entry.getValue().length);
                    jarEntry.setCrc(crc.getValue());
                }
                out.putNextEntry(jarEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Path write(Path dir, String name, byte[] bytes) throws Exception {
        return Files.write(dir.resolve(name), bytes);
    }

    private static void assertBundledLibraryKept(Path translated, String nestedName) throws Exception {
        try (JarFile jar = new JarFile(translated.toFile())) {
            JarEntry nested = jar.getJarEntry(nestedName);
            assertNotNull(nested, nestedName + " must stay in the translated jar");
            Path copy = Files.createTempFile("retromod-nested", ".jar");
            try {
                Files.write(copy, jar.getInputStream(nested).readAllBytes());
                try (JarFile library = new JarFile(copy.toFile())) {
                    assertNotNull(library.getJarEntry("lib/Library.class"), "the library's class must stay");
                }
            } finally {
                Files.deleteIfExists(copy);
            }
        }
    }
}
