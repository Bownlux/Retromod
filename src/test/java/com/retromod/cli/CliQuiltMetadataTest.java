/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.google.gson.JsonParser;
import com.retromod.core.QuiltMetadataCompat;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.embedder.ModVersionInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliQuiltMetadataTest {

    private Field cliTarget;
    private String previousCliTarget;
    private String previousSharedTarget;

    @BeforeEach
    void useDeterministicTarget() throws Exception {
        cliTarget = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        cliTarget.setAccessible(true);
        previousCliTarget = (String) cliTarget.get(null);
        previousSharedTarget = RetromodVersion.TARGET_MC_VERSION;
        cliTarget.set(null, "26.2");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
    }

    @AfterEach
    void restoreTarget() throws Exception {
        cliTarget.set(null, previousCliTarget);
        RetromodVersion.TARGET_MC_VERSION = previousSharedTarget;
    }

    @Test
    void topLevelTransformUpdatesTheQuiltDependencyArray(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("input.jar");
        Path output = directory.resolve("output.jar");
        Files.write(input, jar(Map.of("quilt.mod.json", quiltMetadata())));
        Method transform = RetromodCli.class.getDeclaredMethod(
                "transformJar", Path.class, Path.class, RetromodTransformer.class,
                ModVersionInfo.class);
        transform.setAccessible(true);
        ModVersionInfo info = new ModVersionInfo(
                "quilt-test", "1.0", "1.20.1", "quilt", null,
                Set.of(), Set.of(), false);

        transform.invoke(null, input, output, RetromodTransformer.getInstance(), info);

        assertQuiltTarget(readEntry(output, "quilt.mod.json"));
    }

    @Test
    void nestedTransformUpdatesTheQuiltDependencyArray() throws Exception {
        byte[] nested = jar(Map.of("quilt.mod.json", quiltMetadata()));

        byte[] transformed = RetromodCli.transformNestedJar(nested, 1);

        assertQuiltTarget(readEntry(transformed, "quilt.mod.json"));
    }

    @Test
    void metadataOnlyPatchUpdatesTheQuiltDependencyArray(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("input.jar");
        Files.write(input, jar(Map.of("quilt.mod.json", quiltMetadata())));

        RetromodCli.patchModMetadata(input);

        assertQuiltTarget(readEntry(input, "quilt.mod.json"));
    }

    private static void assertQuiltTarget(byte[] metadata) throws Exception {
        assertEquals("=26.2", QuiltMetadataCompat.readMinecraftVersion(metadata));
        var root = JsonParser.parseString(
                new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("must-stay",
                root.getAsJsonObject("depends").get("minecraft").getAsString());
    }

    private static byte[] quiltMetadata() {
        return ("{\"schema_version\":1,\"quilt_loader\":{"
                + "\"group\":\"test\",\"id\":\"quilt-test\",\"version\":\"1.0\","
                + "\"depends\":[{\"id\":\"quilt_loader\",\"versions\":\">=0.17.0\"},"
                + "{\"id\":\"minecraft\",\"versions\":\"~1.20.1\"}]},"
                + "\"depends\":{\"minecraft\":\"must-stay\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (JarFile input = new JarFile(jar.toFile())) {
            JarEntry entry = input.getJarEntry(name);
            try (var stream = input.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    private static byte[] readEntry(byte[] jar, String name) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jar))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return input.readAllBytes();
            }
        }
        throw new AssertionError("missing entry " + name);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
