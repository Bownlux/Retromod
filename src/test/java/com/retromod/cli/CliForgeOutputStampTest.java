/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.inplace.TranslatedJarMarkers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** CLI output for Forge and NeoForge carries the stamps that tell in-place loading it is already translated. */
class CliForgeOutputStampTest {

    private static final String TOML = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n"
            + "[[mods]]\nmodId=\"stamped\"\nversion=\"1.0\"\n"
            + "[[dependencies.stamped]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.19.2,1.20)\"\n";

    private String savedTarget;

    @BeforeEach
    void target() throws Exception {
        savedTarget = setTarget("1.20.1");
    }

    @AfterEach
    void reset() throws Exception {
        setTarget(savedTarget);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A Forge jar keeps its manifest and gains the runtime transformer's stamps")
    void forgeJarWithManifestIsStamped(@TempDir Path dir) throws Exception {
        Path input = jar(dir.resolve("forge-mod.jar"), true, Map.of("META-INF/mods.toml", TOML));

        Path output = transform(input, "forge");

        Manifest manifest = manifest(output);
        assertTrue(TranslatedJarMarkers.isRetromodOutput(manifest));
        assertEquals("1.20.1", manifest.getMainAttributes().getValue(TranslatedJarMarkers.TARGET_VERSION));
        assertEquals("forge-mod.jar", manifest.getMainAttributes().getValue("Retromod-Original-Jar"));
        assertEquals("2.0", manifest.getMainAttributes().getValue("Implementation-Version"),
                "the mod's own keys are kept");
        assertEquals(JarFile.MANIFEST_NAME, firstFileEntry(output));
    }

    @Test
    @DisplayName("A Forge jar without a manifest gets one, written first")
    void forgeJarWithoutManifestGetsOne(@TempDir Path dir) throws Exception {
        Path input = jar(dir.resolve("bare-forge.jar"), false, Map.of("META-INF/mods.toml", TOML));

        Path output = transform(input, "forge");

        assertTrue(TranslatedJarMarkers.isRetromodOutput(manifest(output)));
        assertEquals(JarFile.MANIFEST_NAME, firstFileEntry(output));
    }

    @Test
    @DisplayName("A NeoForge jar is stamped too")
    void neoForgeJarIsStamped(@TempDir Path dir) throws Exception {
        Path input = jar(dir.resolve("neo-mod.jar"), true, Map.of("META-INF/neoforge.mods.toml", TOML));

        assertTrue(TranslatedJarMarkers.isRetromodOutput(manifest(transform(input, "neoforge"))));
    }

    @Test
    @DisplayName("Fabric and Quilt output gets no Forge stamps, since in-place loading never reads it")
    void fabricAndQuiltOutputIsNotStamped(@TempDir Path dir) throws Exception {
        String fabric = "{\"schemaVersion\":1,\"id\":\"stamped\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.19.2\"}}";
        String quilt = "{\"schema_version\":1,\"quilt_loader\":{\"group\":\"com.example\",\"id\":\"stamped\","
                + "\"version\":\"1.0\",\"depends\":[{\"id\":\"minecraft\",\"versions\":\"1.19.2\"}]}}";

        Manifest fabricManifest = manifest(transform(
                jar(dir.resolve("fabric-mod.jar"), true, Map.of("fabric.mod.json", fabric)), "fabric"));
        Path bareQuilt = transform(jar(dir.resolve("quilt-mod.jar"), false,
                Map.of("quilt.mod.json", quilt)), "quilt");

        assertNull(fabricManifest.getMainAttributes().getValue(TranslatedJarMarkers.TRANSFORMED));
        assertNull(fabricManifest.getMainAttributes().getValue(TranslatedJarMarkers.TARGET_VERSION));
        assertNull(manifest(bareQuilt), "a Quilt jar without a manifest is not given one");
    }

    @Test
    @DisplayName("A 1.12.2 jar with only mcmod.info stays unstamped, so the in-game pass can write its toml")
    void mcmodInfoOnlyJarIsNotStamped(@TempDir Path dir) throws Exception {
        Path input = jar(dir.resolve("legacy-mod.jar"), true, Map.of("mcmod.info",
                "[{\"modid\":\"stamped\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]"));

        assertFalse(TranslatedJarMarkers.isRetromodOutput(manifest(transform(input, "forge"))));
    }

    @Test
    @DisplayName("The target written into the manifest cannot start a new header")
    void targetValueIsSanitized(@TempDir Path dir) throws Exception {
        setTarget("1.20.1\nInjected: yes");
        Path input = jar(dir.resolve("forge-mod.jar"), true, Map.of("META-INF/mods.toml", TOML));

        Attributes main = manifest(transform(input, "forge")).getMainAttributes();

        assertEquals("1.20.1_Injected: yes", main.getValue(TranslatedJarMarkers.TARGET_VERSION));
        assertNull(main.getValue("Injected"));
    }

    /** Runs the CLI's private jar transform for a mod of {@code loader}. */
    private static Path transform(Path input, String loader) throws Exception {
        Path output = input.resolveSibling(input.getFileName().toString().replace(".jar", "-out.jar"));
        Method transform = RetromodCli.class.getDeclaredMethod("transformJar", Path.class, Path.class,
                RetromodTransformer.class, ModVersionInfo.class);
        transform.setAccessible(true);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transform.invoke(null, input, output, transformer, new ModVersionInfo("stamped", "1.0", "1.19.2",
                loader, null, Set.of(), Set.of(), false));
        return output;
    }

    private static String setTarget(String target) throws Exception {
        Field field = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        field.setAccessible(true);
        String previous = (String) field.get(null);
        field.set(null, target);
        return previous;
    }

    /** A jar with these text entries, after a manifest holding Implementation-Version when asked. */
    private static Path jar(Path file, boolean withManifest, Map<String, String> entries) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            if (withManifest) {
                out.putNextEntry(new ZipEntry(JarFile.MANIFEST_NAME));
                out.write("Manifest-Version: 1.0\r\nImplementation-Version: 2.0\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private static Manifest manifest(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest();
        }
    }

    private static String firstFileEntry(Path jar) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) return entry.getName();
            }
        }
        return null;
    }
}
