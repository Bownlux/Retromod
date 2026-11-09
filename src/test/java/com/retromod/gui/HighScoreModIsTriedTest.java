/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.gui.RetromodScreen.ModResult;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mod whose scan scores high is still translated; only {@code aot --skip-risky} skips it. */
class HighScoreModIsTriedTest {

    private static final String HOST = "26.3";
    private static final String FORGE_1_20_1_TOML = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n"
            + "license=\"MIT\"\n[[mods]]\nmodId=\"risky\"\nversion=\"1.0\"\n"
            + "[[dependencies.risky]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
            + "[[dependencies.risky]]\nmodId=\"minecraft\"\nmandatory=true\n"
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

    @Test
    void thePickerTranslatesAModWithAHighScore() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = HOST;
        Path picked = highScoreMod();

        List<ModResult> results = RetromodScreen.updateMods(game, new File[]{picked.toFile()});

        assertEquals(1, results.size(), results.toString());
        assertEquals(ModResult.Status.UPDATED, results.get(0).status(), results.toString());
        assertTrue(Files.isRegularFile(game.resolve("mods/risky-retromod.jar")),
                "the translated jar must reach mods/");
    }

    @Test
    void theCliTranslatesItUnlessSkipRiskyIsSet() throws Exception {
        Path picked = highScoreMod();

        Path byDefault = game.resolve("out/default.jar");
        CliHarness.aot(picked, byDefault, HOST);
        assertTrue(Files.isRegularFile(byDefault), "aot tries every mod by default");

        Path withForce = game.resolve("out/force.jar");
        CliHarness.aot(picked, withForce, HOST, "--force");
        assertTrue(Files.isRegularFile(withForce), "--force is still accepted and changes nothing");

        Path skipped = game.resolve("out/skipped.jar");
        CliHarness.aot(picked, skipped, HOST, "--skip-risky");
        assertFalse(Files.exists(skipped), "--skip-risky skips a mod at or above the skip score");
    }

    /** A Forge 1.20.1 mod with coremods, an access transformer, ASM, and a server call: 65 points. */
    private Path highScoreMod() throws Exception {
        Path jar = Files.createDirectories(game.resolve("picked")).resolve("risky.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            put(out, "META-INF/mods.toml", FORGE_1_20_1_TOML.getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/coremods.json", "{}".getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/accesstransformer.cfg", new byte[0]);
            put(out, "risky/Hooks.class", hooksClass());
        }
        ModComplexityAnalyzer.ComplexityReport report = new ModComplexityAnalyzer().analyze(jar);
        assertTrue(report.highScore(), "the fixture must reach the skip score: " + report);
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    /** Reads a class with ASM and asks the server for its tick count. */
    private static byte[] hooksClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "risky/Hooks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Lorg/objectweb/asm/ClassReader;Lnet/minecraft/server/MinecraftServer;)I", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/objectweb/asm/ClassReader", "getClassName",
                "()Ljava/lang/String;", false);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/MinecraftServer", "getTickCount",
                "()I", false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
