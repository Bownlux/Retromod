/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** An AOT jar keeps the mod's own manifest entries, which Forge reads for mixins, version and module name. */
class AotManifestTest {

    private static final List<String> MOD_KEYS = List.of(
            "MixinConfigs", "Implementation-Version", "Automatic-Module-Name", "FMLModType");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("The mod's manifest keys survive, while signing digests and stale Retromod keys do not")
    void keepsTheModsOwnManifest(@TempDir Path dir) throws Exception {
        Path input = signedForgeMod(dir);
        Manifest original = manifest(input);

        Path output = compiler(dir).compileModAot(input, true);

        Manifest compiled = manifest(output);
        Attributes main = compiled.getMainAttributes();
        for (String key : MOD_KEYS) {
            assertEquals(original.getMainAttributes().getValue(key), main.getValue(key),
                    key + " must be copied from the mod's manifest");
        }
        assertEquals(AotCompiler.AOT_VERSION, main.getValue("Retromod-AOT-Version"));
        assertEquals("26.3", main.getValue("Retromod-Target-Version"));
        assertNull(main.getValue("Retromod-Loader-Api-Runtime"),
                "a Retromod key from an older translation must not describe this compile");
        assertNull(main.getValue("Retromod-JIT-Classes"));
        assertTrue(compiled.getEntries().isEmpty(), "a section holding only a digest is dropped");
        try (JarFile jar = new JarFile(output.toFile())) {
            assertNull(jar.getJarEntry("META-INF/TEST.SF"), "signature files no longer match the classes");
        }
    }

    @Test
    @DisplayName("A cached AOT jar carrying the mod's manifest is still reused")
    void reusesACachedJarWithTheModsManifest(@TempDir Path dir) throws Exception {
        Path input = signedForgeMod(dir);
        AotCompiler compiler = compiler(dir);

        Path first = compiler.compileModAot(input, true);
        String compiledAt = manifest(first).getMainAttributes().getValue("Retromod-Compiled-Time");
        Path second = compiler.compileModAot(input, true);

        assertEquals(first, second);
        assertEquals(compiledAt, manifest(second).getMainAttributes().getValue("Retromod-Compiled-Time"),
                "a cache hit returns the jar it compiled before");
    }

    private static AotCompiler compiler(Path dir) throws Exception {
        return AotCompiler.forOfflineInputs(new ShimRegistry(), "26.3", dir.toRealPath().resolve("cache"));
    }

    /** A Forge 1.20.1 mod with the manifest keys Forge reads, a stale stamp, and a signature. */
    private static Path signedForgeMod(Path dir) throws Exception {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("MixinConfigs", "fixture.mixins.json");
        main.putValue("Implementation-Version", "3.2.1");
        main.putValue("Automatic-Module-Name", "fixture.mod");
        main.putValue("FMLModType", "MOD");
        main.putValue("Retromod-Loader-Api-Runtime", "stale");
        main.putValue("Retromod-JIT-Classes", "test/Stale");
        Attributes digest = new Attributes();
        digest.putValue("SHA-256-Digest", "AAAA");
        manifest.getEntries().put("test/Fixture.class", digest);

        String toml = """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="manifest_fixture"
                version="${file.jarVersion}"
                [[dependencies.manifest_fixture]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1]"
                """;
        Path jar = dir.resolve("manifest-fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            put(out, "META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/TEST.SF", "Signature-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            put(out, "test/Fixture.class", emptyClass("test/Fixture"));
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Manifest manifest(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest();
        }
    }
}
