/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** With the loader API runtime off, the CLI writes the same jars as the pre-runtime build. */
class LoaderApiRuntimeOffOutputTest {

    private static final String GOLDEN = "/loaderapi/off-golden-1.4.0-snapshot.2.tsv";
    private static final String TARGET = "26.3";

    @TempDir
    Path dir;

    private AutoCloseable override;

    @BeforeEach
    void runtimeOff() {
        override = LoaderApiRuntime.overrideForTesting(new LoaderApiSetting.Effective(false,
                LoaderApiSetting.Mode.OFF, LoaderApiSetting.Source.SYSTEM_PROPERTY, "test"), null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @AfterEach
    void restore() throws Exception {
        override.close();
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    // The golden digests come from the 1.4.0-snapshot.2 CLI jar run on LoaderApiFixtures.
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"transform, fabric", "transform, quilt", "aot, fabric", "aot, quilt"})
    @DisplayName("Output matches the pre-runtime CLI entry for entry")
    void outputMatchesGolden(String command, String loader) throws Exception {
        Path input = dir.resolve("loaderapi-fixture-" + loader + ".jar");
        Files.write(input, "fabric".equals(loader) ? LoaderApiFixtures.fabricJar()
                : LoaderApiFixtures.quiltJar());
        Path output = dir.resolve(command + "-" + loader + ".jar");

        if ("transform".equals(command)) {
            CliHarness.transform(input, output, TARGET);
        } else {
            CliHarness.aot(input, output, TARGET);
        }

        if (System.getProperty("loaderapi.keep") != null) {
            Files.copy(output, Path.of(System.getProperty("loaderapi.keep")).resolve(output.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, String> actual = LoaderApiGolden.digests(Files.readAllBytes(output));
        Map<String, String> expected = golden().get(command + "-" + loader);
        assertNotNull(expected, "no golden digests for " + command + "-" + loader);
        assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(actual.keySet()),
                "the off path must write the same entries as 1.4.0-snapshot.2");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()),
                    entry.getKey() + " differs from the 1.4.0-snapshot.2 output with the runtime off");
        }
        String manifest = new String(LoaderApiGolden.normalize("META-INF/MANIFEST.MF",
                readEntry(output, "META-INF/MANIFEST.MF")));
        assertFalse(manifest.contains(LoaderApiRuntime.MANIFEST_ATTRIBUTE),
                "an off-path jar must not carry the runtime identity");
    }

    private static Map<String, Map<String, String>> golden() throws Exception {
        try (InputStream in = LoaderApiRuntimeOffOutputTest.class.getResourceAsStream(GOLDEN)) {
            assertNotNull(in, GOLDEN + " is missing");
            return LoaderApiGolden.read(in);
        }
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (var file = new java.util.jar.JarFile(jar.toFile())) {
            var entry = file.getJarEntry(name);
            return entry == null ? new byte[0] : file.getInputStream(entry).readAllBytes();
        }
    }
}
