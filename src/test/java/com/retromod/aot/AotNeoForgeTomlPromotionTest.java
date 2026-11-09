/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NeoForge 1.20.2 and newer read only neoforge.mods.toml, so AOT renames a Forge mod's toml. */
class AotNeoForgeTomlPromotionTest {

    private static final String FORGE_TOML = "META-INF/mods.toml";
    private static final String NEO_TOML = "META-INF/neoforge.mods.toml";
    private static final String FORGE_MOD = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String NEOFORGE_MOD = "Lnet/neoforged/fml/common/Mod;";

    private static final String FORGE_METADATA = """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="brainrot"
            version="1.0"
            [[dependencies.brainrot]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
            ordering="NONE"
            side="BOTH"
            [[dependencies.brainrot]]
            modId="jei"
            mandatory=false
            versionRange="[15.2,16)"
            ordering="NONE"
            side="BOTH"
            [[dependencies.brainrot]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.21)"
            ordering="NONE"
            side="BOTH"
            """;
    private static final String STALE_NEO_METADATA = """
            modLoader="javafml"
            loaderVersion="[1,)"
            license="MIT"
            [[mods]]
            modId="brainrot"
            version="1.0"
            [[dependencies.brainrot]]
            modId="minecraft"
            type="required"
            versionRange="[1.21.1]"
            """;

    @TempDir
    Path directory;

    @AfterEach
    void reset() {
        McReflect.setForceNeoForge(false);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("a Forge mod compiled for NeoForge 26.3 gets a relaxed neoforge.mods.toml")
    void forgeTomlIsPromotedForNeoForge() throws Exception {
        McReflect.setForceNeoForge(true);
        Path output = compile("26.3", forgeJar(Map.of(FORGE_TOML, FORGE_METADATA)));

        assertNull(entry(output, FORGE_TOML), "NeoForge skips a jar that still has mods.toml");
        String toml = entry(output, NEO_TOML);
        assertNotNull(toml, "the promoted toml must be written");
        assertTrue(toml.contains("modId=\"neoforge\""), toml);
        assertFalse(toml.contains("modId=\"forge\""), toml);
        assertTrue(toml.contains("loaderVersion=\"[1,)\""), toml);
        assertTrue(toml.contains("versionRange=\"[1.20.1,)\""), toml);
        assertTrue(dependencyBlock(toml, "jei").contains("type=\"optional\""),
                "NeoForge ignores mandatory=false and defaults type to required: " + toml);
    }

    @Test
    @DisplayName("a stale neoforge.mods.toml beside Forge metadata is replaced, not duplicated")
    void staleNeoForgeTomlIsReplaced() throws Exception {
        McReflect.setForceNeoForge(true);
        Map<String, String> tomls = new LinkedHashMap<>();
        tomls.put(NEO_TOML, STALE_NEO_METADATA);
        tomls.put(FORGE_TOML, FORGE_METADATA);
        Path output = compile("26.3", forgeJar(tomls));

        String toml = entry(output, NEO_TOML);
        assertNotNull(toml);
        assertTrue(toml.contains("versionRange=\"[1.20.1,)\""),
                "the toml must come from the Forge file the code was built for: " + toml);
        assertFalse(toml.contains("1.21.1"), toml);
        assertNull(entry(output, FORGE_TOML));
    }

    @Test
    @DisplayName("without a NeoForge target the Forge mod keeps mods.toml")
    void forgeTargetKeepsModsToml() throws Exception {
        Path output = compile("26.3", forgeJar(Map.of(FORGE_TOML, FORGE_METADATA)));

        assertNotNull(entry(output, FORGE_TOML));
        assertNull(entry(output, NEO_TOML));
    }

    @Test
    @DisplayName("a NeoForge mod's own tomls keep their names")
    void nativeNeoForgeTomlIsLeftNamed() throws Exception {
        McReflect.setForceNeoForge(true);
        Map<String, String> tomls = new LinkedHashMap<>();
        tomls.put(NEO_TOML, STALE_NEO_METADATA);
        tomls.put(FORGE_TOML, FORGE_METADATA);
        Path output = compile("26.3", jar(NEOFORGE_MOD, tomls));

        assertTrue(entry(output, NEO_TOML).contains("versionRange=\"[1.21.1,)\""),
                "the NeoForge code's own toml is the one kept under its name");
        assertNotNull(entry(output, FORGE_TOML), "nothing is renamed for NeoForge code");
    }

    @Test
    @DisplayName("NeoForge 1.20.1 still reads mods.toml, so the name is kept")
    void neoForge1201KeepsModsToml() throws Exception {
        McReflect.setForceNeoForge(true);
        Path output = compile("1.20.1", forgeJar(Map.of(FORGE_TOML, FORGE_METADATA)));

        assertNotNull(entry(output, FORGE_TOML));
        assertNull(entry(output, NEO_TOML));
    }

    @Test
    @DisplayName("a Forge-shaped cached jar is not served to a NeoForge compile")
    void cacheKeepsLoadersApart() throws Exception {
        Path input = forgeJar(Map.of(FORGE_TOML, FORGE_METADATA));
        Path cache = directory.toRealPath().resolve("cache");

        Path forgeOutput = AotCompiler.forOfflineInputs(new ShimRegistry(), "26.3", cache)
                .compileModAot(input, true);
        assertNotNull(entry(forgeOutput, FORGE_TOML));

        McReflect.setForceNeoForge(true);
        Path neoOutput = AotCompiler.forOfflineInputs(new ShimRegistry(), "26.3", cache)
                .compileModAot(input, true);
        assertNotNull(entry(neoOutput, NEO_TOML), "the NeoForge compile must not reuse the Forge jar");
        assertNull(entry(neoOutput, FORGE_TOML));
    }

    private Path compile(String target, Path input) throws Exception {
        Path output = directory.resolve("output-" + target + ".jar");
        AotCompiler compiler = AotCompiler.forOfflineInputs(
                new ShimRegistry(), target, directory.toRealPath().resolve("cache-" + target));
        ModVersionInfo mod = new ModVersionInfo("brainrot", "1.0", "1.20.1", "forge", null,
                Set.of("brainrot/"), Set.of(), false);
        compiler.compileJar(input, output, mod,
                new AotCompiler.ArchiveBudget(Long.MAX_VALUE, Integer.MAX_VALUE));
        return output;
    }

    private Path forgeJar(Map<String, String> tomls) throws Exception {
        return jar(FORGE_MOD, tomls);
    }

    private Path jar(String modAnnotation, Map<String, String> tomls) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "brainrot/Brainrot", null,
                "java/lang/Object", null);
        writer.visitAnnotation(modAnnotation, true).visit("value", "brainrot");
        writer.visitEnd();
        Path jar = Files.createTempFile(directory, "brainrot-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> toml : tomls.entrySet()) {
                out.putNextEntry(new JarEntry(toml.getKey()));
                out.write(toml.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("brainrot/Brainrot.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    /** The text of one jar entry, or null when the jar lacks it. */
    private static String entry(Path jarPath, String name) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(name);
            if (entry == null) return null;
            try (InputStream input = jar.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** The dependency block for {@code modId}, which the test fixtures always contain. */
    private static String dependencyBlock(String toml, String modId) {
        for (String block : toml.split("(?=\\[\\[dependencies\\.)")) {
            if (block.contains("modId=\"" + modId + "\"")) return block;
        }
        throw new AssertionError("no dependency block for " + modId + " in " + toml);
    }

    @Test
    @DisplayName("one type line is added to an old optional dependency")
    void promotedOptionalDependencyHasOneType() throws Exception {
        McReflect.setForceNeoForge(true);
        String jei = dependencyBlock(entry(compile("26.3",
                forgeJar(Map.of(FORGE_TOML, FORGE_METADATA))), NEO_TOML), "jei");

        assertEquals(1, jei.split("type\\s*=", -1).length - 1, jei);
        assertTrue(jei.contains("versionRange=\"[0,)\""), "an optional sibling is open: " + jei);
    }
}
