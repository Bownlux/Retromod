/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AOT relaxes a toml by the same rules as the CLI and the in-game path. */
class AotTomlRelaxTest {

    private static final String NEO_TOML = "META-INF/neoforge.mods.toml";

    /** Shaped like Supplementaries for NeoForge 1.21.1, with a cristellib-style closed range. */
    private static final String SUPPLEMENTARIES = """
            modLoader="javafml"
            loaderVersion="[4,5)"
            license="All Rights Reserved"
            [[mods]]
            modId="supplementaries"
            version="4.0.2"
            [[dependencies.supplementaries]]
            modId="minecraft"
            type="required"
            versionRange="[1.21.1,1.21.2)"
            ordering="NONE"
            side="BOTH"
            [[dependencies.supplementaries]]
            modId="farmersdelight"
            type="incompatible"
            versionRange="[0,1.3.0)"
            ordering="NONE"
            side="BOTH"
            [[dependencies.supplementaries]]
            modId="expanded_ecosphere"
            type="incompatible"
            versionRange="[0,3.4.3]"
            ordering="NONE"
            side="BOTH"
            [[dependencies.supplementaries]]
            modId="moonlight"
            mandatory=true
            versionRange="[1.21-2.17.4,)"
            ordering="AFTER"
            side="BOTH"
            [[dependencies.supplementaries]]
            modId="amendments"
            type="required"
            versionRange="[1.20.1-1.2.4,)"
            ordering="NONE"
            side="BOTH"
            """;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("the outer jar keeps avoid-ranges and opens siblings and loaderVersion")
    void outerTomlFollowsTheSharedRules(@TempDir Path directory) throws Exception {
        String toml = outerToml(directory);

        assertEquals("versionRange=\"[0,1.3.0)\"", rangeLine(block(toml, "farmersdelight")),
                "an incompatible range lists versions to avoid, so widening it rejects good pairs");
        assertEquals("versionRange=\"[0,3.4.3]\"", rangeLine(block(toml, "expanded_ecosphere")));
        String moonlight = block(toml, "moonlight");
        assertTrue(moonlight.contains("mandatory=false"), moonlight);
        assertTrue(moonlight.contains("type=\"optional\""),
                "NeoForge reads only type and defaults it to required: " + moonlight);
        String amendments = block(toml, "amendments");
        assertTrue(amendments.contains("type=\"optional\""), amendments);
        assertEquals("versionRange=\"[0,)\"", rangeLine(amendments),
                "a present optional dependency is still range-checked");
        assertTrue(toml.contains("loaderVersion=\"[1,)\""), toml);
        assertEquals("versionRange=\"[1.21.1,)\"", rangeLine(block(toml, "minecraft")));
    }

    @Test
    @DisplayName("a bundled jar's toml gets exactly the outer jar's relaxation")
    void nestedTomlMatchesTheOuterJar(@TempDir Path directory) throws Exception {
        byte[] library = jarOf(NEO_TOML, SUPPLEMENTARIES.getBytes(StandardCharsets.UTF_8));
        byte[] parent = jarOf("META-INF/jarjar/lib.jar", library);
        Method nested = AotCompiler.class.getDeclaredMethod("transformNestedJarAot",
                byte[].class, int.class, MixinCompatibilityTransformer.class, boolean.class);
        nested.setAccessible(true);
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.3",
                directory.toRealPath().resolve("nested-cache"));

        byte[] output = (byte[]) nested.invoke(compiler, parent, 1,
                new MixinCompatibilityTransformer(RetromodTransformer.getInstance()), true);
        String nestedToml = new String(entry(entry(output, "META-INF/jarjar/lib.jar"), NEO_TOML),
                StandardCharsets.UTF_8);

        assertEquals(outerToml(directory), nestedToml);
    }

    private static String outerToml(Path directory) throws Exception {
        Path input = Files.createTempFile(directory, "supplementaries-", ".jar");
        Files.write(input, jarOf(NEO_TOML, SUPPLEMENTARIES.getBytes(StandardCharsets.UTF_8)));
        Path output = directory.resolve(input.getFileName() + "-out.jar");
        AotCompiler compiler = new AotCompiler(new ShimRegistry(), "26.3",
                directory.toRealPath().resolve("cache"));
        ModVersionInfo mod = new ModVersionInfo("supplementaries", "4.0.2", "1.21.1", "neoforge",
                null, Set.of("net/mehvahdjukaar/"), Set.of(), false);
        compiler.compileJar(input, output, mod,
                new AotCompiler.ArchiveBudget(Long.MAX_VALUE, Integer.MAX_VALUE));
        try (ZipFile jar = new ZipFile(output.toFile());
             InputStream in = jar.getInputStream(jar.getEntry(NEO_TOML))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String block(String toml, String modId) {
        for (String block : toml.split("(?=\\[\\[dependencies\\.)")) {
            if (block.contains("modId=\"" + modId + "\"")) return block;
        }
        throw new AssertionError("no dependency block for " + modId + " in " + toml);
    }

    private static String rangeLine(String block) {
        return block.lines().filter(line -> line.startsWith("versionRange")).findFirst()
                .orElseThrow(() -> new AssertionError("no versionRange in " + block));
    }

    private static byte[] jarOf(String name, byte[] data) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(name));
            jar.write(data);
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] entry(byte[] jarBytes, String name) throws Exception {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            for (JarEntry entry; (entry = jar.getNextJarEntry()) != null; ) {
                if (name.equals(entry.getName())) return jar.readAllBytes();
            }
        }
        throw new AssertionError("missing " + name);
    }
}
