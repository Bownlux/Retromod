/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.QuiltMetadataCompat;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loader checks the outer jar's ranges before any class loads, so AOT relaxes them for every target. */
class AotMetadataRelaxationTest {

    @TempDir
    Path directory;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    void forgeMinecraftRangeIsOpenedAndAvoidRangeKept(String target) throws Exception {
        String toml = """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="relaxfixture"
                version="1.0"
                [[dependencies.relaxfixture]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.21)"
                [[dependencies.relaxfixture]]
                modId="oldlib"
                type="incompatible"
                versionRange="[1.0,2.0)"
                """;
        String output = compiledEntry(target, "forge", "META-INF/mods.toml", toml);

        assertTrue(output.contains("versionRange=\"[1.20.1,)\""),
                "the host must fit the minecraft range: " + output);
        assertTrue(output.contains("versionRange=\"[1.0,2.0)\""),
                "an incompatible range lists versions to avoid: " + output);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    void fabricMinecraftVersionIsOpened(String target) throws Exception {
        String json = """
                {"schemaVersion":1,"id":"relaxfixture","version":"1.0",
                 "depends":{"fabricloader":">=0.14.21","minecraft":"1.20.1"}}
                """;
        String output = compiledEntry(target, "fabric", "fabric.mod.json", json);

        assertTrue(output.contains("\"minecraft\":\"*\""), output);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    void quiltMinecraftVersionIsTheTarget(String target) throws Exception {
        String json = """
                {"schema_version":1,"quilt_loader":{
                  "group":"fixture","id":"relaxfixture","version":"1.0",
                  "depends":[{"id":"minecraft","versions":"~1.20.1"}]}}
                """;
        String output = compiledEntry(target, "fabric", "quilt.mod.json", json);

        assertEquals("=" + target,
                QuiltMetadataCompat.readMinecraftVersion(output.getBytes(StandardCharsets.UTF_8)));
    }

    /** Compiles a one-file 1.20.1 mod for {@code target} and returns its metadata file. */
    private String compiledEntry(String target, String shimLoader, String name, String metadata)
            throws Exception {
        Path input = directory.resolve("relaxfixture-" + target + ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry(name));
            jar.write(metadata.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        ShimRegistry registry = new ShimRegistry();
        // Forge's version graph files 1.20.1 under its 1.20 node.
        String source = "forge".equals(shimLoader) ? "1.20" : "1.20.1";
        registry.register(new NoopShim(shimLoader, source, target));
        Path output = AotCompiler.forOfflineInputs(registry, target,
                directory.toRealPath().resolve("cache-" + target)).compileModAot(input, true);

        assertNotEquals(input, output, "the mod must be compiled, not passed through");
        try (ZipFile jar = new ZipFile(output.toFile());
             InputStream in = jar.getInputStream(jar.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(" ", "");
        }
    }

    /** A 1.20.1-to-target step that registers nothing, so the compile has a chain to run. */
    private record NoopShim(String loader, String source, String target) implements VersionShim {
        @Override public String getShimName() { return "Metadata relaxation fixture"; }
        @Override public String getSourceVersion() { return source; }
        @Override public String getTargetVersion() { return target; }
        @Override public String getModLoaderType() { return loader; }
        @Override public void registerRedirects(RetromodTransformer transformer) {}
    }
}
