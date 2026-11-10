/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** In rewrite mode the picker translates a Forge-family jar the way startup does. */
class PickerRewriteTest {

    private static final String FORGE_1_20_1_TOML = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n"
            + "license=\"MIT\"\n[[mods]]\nmodId=\"spaced\"\nversion=\"1.0\"\n"
            + "[[dependencies.spaced]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
            + "[[dependencies.spaced]]\nmodId=\"minecraft\"\nmandatory=true\n"
            + "versionRange=\"[1.20.1,1.21)\"\n";

    @TempDir
    Path game;

    private String savedTarget;

    @BeforeEach
    void save() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(false);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    void neoForgeHostGetsASafeNameAndTheTomlItReads(String host) throws Exception {
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = host;
        // A space in the file name breaks the module name NeoForge derives from it.
        Path picked = jar("Spaced Mod 1.0.jar", Map.of("META-INF/mods.toml",
                FORGE_1_20_1_TOML.getBytes(StandardCharsets.UTF_8)));

        Path result = new ModCompatibilityChecker(game).transformAndInstall(picked);

        assertEquals(game.resolve("mods/Spaced_Mod_1.0-retromod.jar"), result);
        assertNull(entry(result, "META-INF/mods.toml"), "NeoForge 1.20.5 and newer skip mods.toml");
        String toml = entry(result, "META-INF/neoforge.mods.toml");
        assertNotNull(toml, "the promoted metadata is neoforge.mods.toml");
        assertTrue(toml.contains("versionRange = \"[" + host + ",)\""), toml);
        assertTrue(toml.contains("modId=\"neoforge\""), "NeoForge has no forge mod: " + toml);
        assertTrue(toml.contains("loaderVersion=\"[1,)\""), toml);
        assertTrue(ForgeModTransformer.isTransformedMod(result),
                "the jar carries the startup flow's stamp");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    void forgeHostKeepsModsTomlWithTheHostRange(String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        Path picked = jar("forge-mod.jar", Map.of("META-INF/mods.toml",
                FORGE_1_20_1_TOML.getBytes(StandardCharsets.UTF_8)));

        Path result = new ModCompatibilityChecker(game).transformAndInstall(picked);

        String toml = entry(result, "META-INF/mods.toml");
        assertNotNull(toml, "Forge reads mods.toml");
        assertTrue(toml.contains("versionRange = \"[" + host + ",)\""),
                "the old range would make Forge refuse the jar: " + toml);
        assertNull(entry(result, "META-INF/neoforge.mods.toml"));
    }

    @Test
    void failedTranslationIsReportedAsAnError() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        String oldHelper = "legacy/api/Helper";
        String synthetic = "com/retromod/generated/forge/Helper";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(oldHelper, synthetic);
        transformer.registerSyntheticClass(synthetic, classWithFieldOf(synthetic, "java/lang/Object"));
        // A class already sits where the stand-in's copy would go, so the translation fails.
        String taken = SyntheticEmbedder.embeddedBase("collision.jar") + synthetic;
        Path picked = jar("collision.jar", Map.of(
                "META-INF/mods.toml", FORGE_1_20_1_TOML.getBytes(StandardCharsets.UTF_8),
                "fixture/UsesHelper.class", classWithFieldOf("fixture/UsesHelper", oldHelper),
                taken + ".class", classWithFieldOf(taken, "java/lang/Object")));

        IOException error = assertThrows(IOException.class,
                () -> new ModCompatibilityChecker(game).transformAndInstall(picked));

        assertTrue(error.getMessage().contains("collision.jar"), error.getMessage());
        assertFalse(Files.exists(game.resolve("mods/collision-retromod.jar")),
                "a failed translation installs nothing");
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = Files.createDirectories(game.resolve("picked")).resolve(name);
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String entry(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(name);
            if (entry == null) return null;
            try (var in = file.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static byte[] classWithFieldOf(String name, String fieldType) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "helper", "L" + fieldType + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
