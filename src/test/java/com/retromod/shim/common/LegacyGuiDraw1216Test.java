/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** GUI drawing calls whose 26.x bridges now also apply from 1.21.6, where Mojang changed them. */
class LegacyGuiDraw1216Test {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphics";
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String GUI_CALLS = "com/retromod/polyfill/minecraft/LegacyGuiCalls";
    private static final String DRAW_STRING = "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)";
    private static final String RL = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String FACTORY_SPRITE = "(Ljava/util/function/Function;" + RL + "IIII)V";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String MATRIX_STACK = "org/joml/Matrix3x2fStack";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @ParameterizedTest
    @ValueSource(strings = {"neoforge", "forge"})
    void drawStringReturnsNothingFrom1216AndTheCallerGetsZero(String loader) {
        host(loader, "1.21.6");

        MethodInsnNode call = onlyCall(probe(this::drawStringCall));
        assertEquals(GUI + ".drawString" + DRAW_STRING + "V", describe(call), "1.21.6 keeps the name");
        assertEquals(ICONST_0, call.getNext().getOpcode(), "the caller still pops an int");
    }

    @Test
    void drawStringKeepsItsIntBefore1216() {
        host("neoforge", "1.21.5");

        MethodInsnNode call = onlyCall(probe(this::drawStringCall));
        assertEquals(GUI + ".drawString" + DRAW_STRING + "I", describe(call));
    }

    @Test
    void drawStringOnTwentySixThreeStillBecomesText() {
        host("neoforge", "26.3");

        MethodInsnNode call = onlyCall(probe(this::drawStringCall));
        assertEquals(EXTRACTOR + ".text" + DRAW_STRING + "V", describe(call));
        assertEquals(ICONST_0, call.getNext().getOpcode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.21.6", "1.21.11", "26.3"})
    void factorySpriteBlitDrawsThroughThePipelineHelperFrom1216(String version) {
        host("neoforge", version);

        MethodInsnNode call = onlyCall(probe(this::factorySpriteCall));
        assertEquals(GUI_CALLS + ".blitSprite(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;IIII)V",
                describe(call), "on " + version);
    }

    @Test
    void factorySpriteBlitStaysBefore1216() {
        host("forge", "1.21.5");

        assertEquals(GUI + ".blitSprite" + FACTORY_SPRITE, describe(onlyCall(probe(this::factorySpriteCall))));
    }

    @Test
    void poseMigrationStartsAt1216() {
        assertFalse(Gui2DTransformMigration.appliesOnHost("1.21.5"), "1.21.5 still returns a PoseStack");
        assertTrue(Gui2DTransformMigration.appliesOnHost("1.21.6"));
        assertTrue(Gui2DTransformMigration.appliesOnHost("1.21.11"));
        assertTrue(Gui2DTransformMigration.appliesOnHost("26.3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"neoforge", "forge"})
    void the1216ShimsShipTheRotationHelperTheMigrationCalls(String loader) {
        host(loader, "1.21.5");
        assertFalse(transformer.getSyntheticClasses().containsKey(Quat2DSynthetic.INTERNAL));

        host(loader, "1.21.6");
        assertTrue(transformer.getSyntheticClasses().containsKey(Quat2DSynthetic.INTERNAL),
                "a migrated mulPose calls RetroQuat2D.zAngle");
    }

    @Test
    void theForgeFamilyPathMigratesPoseFrom1216() throws Exception {
        assertEquals(List.of(MATRIX_STACK + ".pushMatrix"), poseOps(forgePath("1.21.6")));
        assertEquals(List.of(POSE_STACK + ".pushPose"), poseOps(forgePath("1.21.5")));
    }

    @Test
    void theOfflinePathMigratesPoseFrom1216() throws Exception {
        assertEquals(List.of(MATRIX_STACK + ".pushMatrix"), poseOps(cliPath("1.21.6")));
        assertEquals(List.of(POSE_STACK + ".pushPose"), poseOps(cliPath("1.21.5")));
    }

    /** Registers every shim the loader's entry point would register on this host. */
    private void host(String loader, String version) {
        RetromodVersion.TARGET_MC_VERSION = version;
        transformer.clearRedirectsForTesting();
        Set<String> types = "neoforge".equals(loader) ? Set.of("neoforge", "forge", "common")
                : Set.of("forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, version)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private byte[] forgePath(String version) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = version;
        transformer.clearRedirectsForTesting();
        Path output = Files.createDirectories(dir.resolve("forge-" + version));
        Path translated = new ForgeModTransformer(version).transformMod(oldNeoForgeMod(version), output);
        assertNotNull(translated, "the mod must translate for " + version);
        return readEntry(translated, "probe/Hud.class");
    }

    private byte[] cliPath(String version) throws Exception {
        Path output = dir.resolve("cli-" + version + ".jar");
        CliHarness.transform(oldNeoForgeMod(version), output, version);
        return readEntry(output, "probe/Hud.class");
    }

    /** A NeoForge 1.21.1 mod whose HUD pushes the GUI pose. */
    private Path oldNeoForgeMod(String version) throws Exception {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"posehud\"\nversion=\"1.0\"\n"
                + "[[dependencies.posehud]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                + "versionRange=\"[1.21.1,1.21.2)\"\n";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "probe/Hud", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "(L" + GUI + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, GUI, "pose", "()L" + POSE_STACK + ";", false);
        m.visitMethodInsn(INVOKEVIRTUAL, POSE_STACK, "pushPose", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        Path jar = dir.resolve("posehud-" + version + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("probe/Hud.class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    /** The pose stack operations the HUD's draw method calls, as owner.name. */
    private static List<String> poseOps(byte[] hud) {
        ClassNode node = new ClassNode();
        new ClassReader(hud).accept(node, 0);
        List<String> ops = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (MethodInsnNode call : calls(method)) {
                if (!call.name.equals("pose")) ops.add(call.owner + "." + call.name);
            }
        }
        return ops;
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(name);
            assertNotNull(entry, name + " in " + jar.getFileName());
            try (var input = archive.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private void drawStringCall(MethodVisitor m) {
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ACONST_NULL);
        m.visitLdcInsn("old");
        m.visitInsn(ICONST_0);
        m.visitInsn(ICONST_0);
        m.visitInsn(ICONST_M1);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, GUI, "drawString", DRAW_STRING + "I", false);
        m.visitInsn(POP);
    }

    private void factorySpriteCall(MethodVisitor m) {
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ACONST_NULL);
        for (int i = 0; i < 4; i++) m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKEVIRTUAL, GUI, "blitSprite", FACTORY_SPRITE, false);
    }

    /** A mod class whose {@code run(GuiGraphics)} holds the body, after the transform. */
    private MethodNode probe(Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "probe/OldScreen", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(L" + GUI + ";)V", null, null);
        m.visitCode();
        body.accept(m);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "probe/OldScreen.class")).accept(node, 0);
        return node.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static MethodInsnNode onlyCall(MethodNode method) {
        List<MethodInsnNode> calls = calls(method);
        assertEquals(1, calls.size(), "expected one call, found "
                + calls.stream().map(LegacyGuiDraw1216Test::describe).toList());
        return calls.get(0);
    }

    private static String describe(MethodInsnNode call) {
        return call.owner + "." + call.name + call.desc;
    }
}
