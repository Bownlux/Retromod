/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** With the runtime on, every transform path writes the same classes. */
class LoaderApiPathParityTest {

    private static final String HOST = "26.3";
    // Each path relocates per-mod helpers under its own archive key, so that prefix is normalized.
    private static final Pattern EMBEDDED = Pattern.compile("com/retromod/embedded/[A-Za-z0-9_.-]+?/");
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        transformer.clearRedirectsForTesting();
        runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("A Quilt mod gets the same classes as the matching Fabric mod")
    void quiltMatchesFabric() throws Exception {
        Path fabric = dir.resolve("loaderapi-fixture-fabric.jar");
        Path quilt = dir.resolve("loaderapi-fixture-quilt.jar");
        Files.write(fabric, LoaderApiFixtures.fabricJar());
        Files.write(quilt, LoaderApiFixtures.quiltJar());
        RetromodPreLaunch.registerShimsForTransform(HOST);
        Path fabricOut = new FabricModTransformer(HOST).transformMod(fabric,
                Files.createDirectory(dir.resolve("fabric-out")));
        Path quiltOut = new QuiltModTransformer(HOST).transformMod(quilt,
                Files.createDirectory(dir.resolve("quilt-out")));

        Map<String, String> fabricClasses = classes(Files.readAllBytes(fabricOut), "fixture/");
        Map<String, String> quiltClasses = classes(Files.readAllBytes(quiltOut), "fixture/");
        assertFalse(fabricClasses.isEmpty());
        assertEquals(fabricClasses, quiltClasses);
    }

    @Test
    @DisplayName("A nested library gets the same classes on the runtime, CLI and AOT paths")
    void nestedLibraryMatchesAcrossPaths() throws Exception {
        Path input = dir.resolve("loaderapi-fixture-fabric.jar");
        Files.write(input, LoaderApiFixtures.fabricJar());

        RetromodPreLaunch.registerShimsForTransform(HOST);
        Path runtimeOut = new FabricModTransformer(HOST).transformMod(input,
                Files.createDirectory(dir.resolve("mods")));
        Path cliOut = dir.resolve("cli.jar");
        CliHarness.transform(input, cliOut, HOST);
        Path aotOut = dir.resolve("aot.jar");
        CliHarness.aot(input, aotOut, HOST);

        String nested = "META-INF/jars/loaderapi-fixture-lib.jar";
        Map<String, String> runtimeLib = classes(entry(runtimeOut, nested), "fixturelib/");
        assertFalse(runtimeLib.isEmpty());
        assertEquals(runtimeLib, classes(entry(cliOut, nested), "fixturelib/"), "CLI differs");
        assertEquals(runtimeLib, classes(entry(aotOut, nested), "fixturelib/"), "AOT differs");
    }

    private static byte[] entry(Path jar, String name) throws Exception {
        try (var file = new java.util.jar.JarFile(jar.toFile())) {
            return file.getInputStream(file.getJarEntry(name)).readAllBytes();
        }
    }

    /** Class entries under {@code prefix}, as text with the per-mod relocation normalized. */
    private static Map<String, String> classes(byte[] jar, String prefix) throws Exception {
        Map<String, String> classes = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.getName().startsWith(prefix) || !entry.getName().endsWith(".class")) continue;
                String text = textOf(in.readAllBytes());
                classes.put(entry.getName(), EMBEDDED.matcher(text).replaceAll("com/retromod/embedded/MOD/"));
            }
        }
        return classes;
    }

    private static String textOf(byte[] classBytes) {
        var writer = new java.io.StringWriter();
        new org.objectweb.asm.ClassReader(classBytes).accept(new org.objectweb.asm.util.TraceClassVisitor(
                new java.io.PrintWriter(writer)), 0);
        return writer.toString();
    }
}
