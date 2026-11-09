/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.cli.RetromodCli;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.shim.forge.Forge_1_20_to_NeoForge_1_21;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #330: MCreator Forge jars that also carry a stale neoforge.mods.toml claiming the host version. */
class ForgeTomlChoiceTest {

    // The display names tell the files apart once both minecraft ranges are rewritten to [host,).
    private static final String STALE_NEO = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"brainrot\"\n"
            + "displayName=\"NeoForge export\"\n"
            + "[[dependencies.brainrot]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.1]\"\n";
    private static final String FORGE = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"brainrot\"\n"
            + "displayName=\"Forge build\"\n"
            + "[[dependencies.brainrot]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.1,1.21)\"\n";
    private static final String NEOFORGE_MOD = "Lnet/neoforged/fml/common/Mod;";

    @TempDir
    Path dir;

    private Field cliTarget;
    private String savedCliTarget;
    private String savedTarget;

    @BeforeEach
    void save() throws Exception {
        cliTarget = RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        cliTarget.setAccessible(true);
        savedCliTarget = (String) cliTarget.get(null);
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restore() throws Exception {
        McReflect.setForceNeoForge(false);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        cliTarget.set(null, savedCliTarget);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    void forgeCodeReadsTheForgeMetadata() throws IOException {
        Path jar = jar("Lnet/minecraftforge/fml/common/Mod;");
        try (JarFile file = new JarFile(jar.toFile())) {
            assertEquals(ForgeTomlChoice.FORGE_TOML, ForgeTomlChoice.metadataEntry(file));
        }
        assertEquals("1.20.1", new ModVersionDetector().detectVersion(jar).targetMcVersion(),
                "the mod is converted from 1.20.1, not left as a 1.21.1 mod");
    }

    @Test
    void neoForgeCodeKeepsTheNeoForgeMetadata() throws IOException {
        try (JarFile file = new JarFile(jar("Lnet/neoforged/fml/common/Mod;").toFile())) {
            assertEquals(ForgeTomlChoice.NEOFORGE_TOML, ForgeTomlChoice.metadataEntry(file));
        }
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    void neoForgeHostShipsThePromotedForgeMetadata(String host) throws IOException {
        neoForgeHost(host);

        Path out = new ForgeModTransformer(host).transformMod(
                jar("Lnet/minecraftforge/fml/common/Mod;"), Files.createDirectory(dir.resolve("out")));

        assertNotNull(out);
        assertPromotedForgeBuild(readEntries(Files.readAllBytes(out)));
    }

    @Test
    void neoForgeCodeKeepsItsNeoForgeMetadata() throws IOException {
        // At 1.21.1 this jar is already current and copied unchanged, so 26.3 reaches promotion.
        neoForgeHost("26.3");

        Path out = new ForgeModTransformer("26.3").transformMod(
                jar(NEOFORGE_MOD), Files.createDirectory(dir.resolve("out")));

        assertNotNull(out);
        String neoToml = readEntries(Files.readAllBytes(out)).get(ForgeTomlChoice.NEOFORGE_TOML);
        assertTrue(neoToml.contains("NeoForge export"), neoToml);
    }

    @Test
    void bundledForgeModShipsThePromotedForgeMetadata() throws IOException {
        neoForgeHost("26.3");
        Path outer = dir.resolve("outer.jar");
        try (OutputStream file = Files.newOutputStream(outer); JarOutputStream out = new JarOutputStream(file)) {
            put(out, ForgeTomlChoice.FORGE_TOML, ("modLoader=\"javafml\"\n[[mods]]\nmodId=\"outer\"\n"
                    + "[[dependencies.outer]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.1,1.21)\"\n")
                    .getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/jarjar/brainrot.jar",
                    Files.readAllBytes(jar("Lnet/minecraftforge/fml/common/Mod;")));
        }

        Path out = new ForgeModTransformer("26.3").transformMod(outer, Files.createDirectory(dir.resolve("out")));

        assertNotNull(out);
        byte[] nested = readBytes(out, "META-INF/jarjar/brainrot.jar");
        assertPromotedForgeBuild(readEntries(nested));
    }

    @Test
    void cliShipsThePromotedForgeMetadata() throws Exception {
        neoForgeHost("26.3");
        cliTarget.set(null, "26.3");
        Path input = jar("Lnet/minecraftforge/fml/common/Mod;");
        Path output = dir.resolve("cli-output.jar");
        ModVersionInfo info = new ModVersionDetector().detectVersion(input);
        Method transform = RetromodCli.class.getDeclaredMethod("transformJar", Path.class,
                Path.class, RetromodTransformer.class, ModVersionInfo.class);
        transform.setAccessible(true);

        // A leftover written beside the renamed mods.toml would stop the CLI on a duplicate entry.
        transform.invoke(null, input, output, RetromodTransformer.getInstance(), info);

        assertPromotedForgeBuild(readEntries(Files.readAllBytes(output)));
    }

    /** A NeoForge host of {@code version} with the Forge to NeoForge shim, which redirects {@code @Mod}. */
    private static void neoForgeHost(String version) {
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = version;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);
    }

    private static void assertPromotedForgeBuild(java.util.Map<String, String> entries) {
        assertNull(entries.get(ForgeTomlChoice.FORGE_TOML), "mods.toml is promoted, not kept");
        String neoToml = entries.get(ForgeTomlChoice.NEOFORGE_TOML);
        assertNotNull(neoToml, "the promoted metadata is neoforge.mods.toml");
        assertTrue(neoToml.contains("Forge build"), "the Forge build's metadata ships: " + neoToml);
        assertFalse(neoToml.contains("NeoForge export"), "the leftover is replaced: " + neoToml);
        assertTrue(entries.get("brainrot/Brainrot.class").contains(NEOFORGE_MOD),
                "the class pass redirected @Mod before promotion");
    }

    /** Every entry of a jar, read as ISO-8859-1 text so class constants stay searchable. */
    private static java.util.Map<String, String> readEntries(byte[] jar) throws IOException {
        java.util.Map<String, String> entries = new java.util.HashMap<>();
        try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
        return entries;
    }

    private static byte[] readBytes(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(name);
            assertNotNull(entry, name + " should be in " + jar);
            try (var in = file.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private Path jar(String modAnnotation) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "brainrot/Brainrot", null, "java/lang/Object", null);
        cw.visitAnnotation(modAnnotation, true).visitEnd();
        cw.visitEnd();
        Path jar = dir.resolve(modAnnotation.contains("neoforged") ? "neo.jar" : "forge.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            put(out, "META-INF/neoforge.mods.toml", STALE_NEO.getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/mods.toml", FORGE.getBytes(StandardCharsets.UTF_8));
            put(out, "brainrot/Brainrot.class", cw.toByteArray());
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
