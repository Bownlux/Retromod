/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Item transforms on a stack, prefixed dynamic textures, the culled translucent type, the blend equation and sized builders. */
class LegacyRenderGapsBridgeTest {

    private static final String HELPER = LegacyRenderGapsBridge.HELPER;
    private static final String BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final Path LIBRARIES = Path.of(System.getProperty("user.home", ""),
            "Library/Application Support/PrismLauncher/libraries");
    private String savedVersion;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("offline for 26.3 each old call reaches its helper, the culled type or nothing, and still verifies")
    void offline263() throws Exception {
        ClassNode out = transformOldCalls("26.3", name -> null);
        List<String> refs = refs(out);

        assertTrue(refs.contains(HELPER + ".apply(L" + LegacyRenderGapsBridge.ITEM_TRANSFORM + ";ZLcom/mojang/blaze3d/vertex/PoseStack;)V"),
                refs.toString());
        assertTrue(refs.contains(HELPER + ".register(L" + LegacyRenderGapsBridge.TEXTURES + ";Ljava/lang/String;"
                + LegacyRenderGapsBridge.DYNAMIC + ")" + LegacyRenderGapsBridge.IDENTIFIER), refs.toString());
        assertTrue(refs.contains(LegacyRenderGapsBridge.RENDER_TYPES + ".entityTranslucentCull" + LegacyRenderGapsBridge.BY_ID), refs.toString());
        assertTrue(refs.contains(RenderBufferSynthetics.TES + ".legacyBuilder(I)L" + BUILDER + ";"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("blendEquation") || r.contains("process(F)V")), refs.toString());
        assertTrue(refs.contains(HELPER + ".postChain(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)L"
                + LegacyRenderGapsBridge.POST_CHAIN + ";"), refs.toString());
        assertTrue(refs.contains(HELPER + ".currentEffect(Ljava/lang/Object;)L" + LegacyRenderGapsBridge.POST_CHAIN + ";"), refs.toString());
        assertTrue(refs.contains(HELPER + ".postChainName(Ljava/lang/Object;)Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains(HELPER + ".signMaterials()Ljava/util/Map;"), refs.toString());
        assertTrue(refs.contains(HELPER + ".levelTicks(Ljava/lang/Object;)I"), refs.toString());
        assertTrue(refs.contains(HELPER + ".getBoundFramebuffer()I"), refs.toString());
        assertTrue(refs.contains(LegacyRenderGapsBridge.MONITOR + ".x()I"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("initGameThread")), refs.toString());
        assertTrue(refs.contains(HELPER + ".signSheet()" + LegacyRenderGapsBridge.IDENTIFIER), refs.toString());
        assertTrue(refs.contains(LegacyRenderGapsBridge.RENDER_TYPES + ".lines()L" + LegacyRenderGapsBridge.RENDER_TYPE + ";"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains(".pick(F)V")), refs.toString());
        assertTrue(refs.contains(HELPER + ".shouldRenderOffScreen(Ljava/lang/Object;Ljava/lang/Object;)Z"), refs.toString());
        assertTrue(refs.contains(HELPER + ".blendPixel(L" + LegacyRenderGapsBridge.NATIVE_IMAGE + ";III)V"), refs.toString());
        for (MethodNode method : out.methods) new Analyzer<>(new BasicVerifier()).analyze(out.name, method);
    }

    @Test
    @DisplayName("offline, Monitor.getMonitor() becomes monitor() for 26.2 but stays for 26.3, which dropped the handle")
    void offlineMonitorHandle() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodTransformer t = RetromodTransformer.getInstance();
        for (String version : new String[]{"26.2", "26.3"}) {
            RetromodVersion.TARGET_MC_VERSION = version;
            t.clearRedirectsForTesting();
            LegacyRenderGapsBridge.registerMonitor(t, null);
            boolean renamed = t.getMethodRedirects().containsKey(
                    new RetromodTransformer.MethodKey(LegacyRenderGapsBridge.MONITOR, "getMonitor", "()J"));
            assertEquals(version.equals("26.2"), renamed, version);
        }
    }

    @ParameterizedTest(name = "{1}")
    @DisplayName("real hosts: only 26.3 has the culled translucent type, every 26.x host gets the other bridges")
    @CsvSource({
            "26.1.2, com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar, entityTranslucent",
            "26.2, com/mojang/minecraft/26.2/minecraft-26.2-client.jar, entityTranslucent",
            "26.3, com/mojang/minecraft/26.3/minecraft-26.3-client.jar, entityTranslucentCull",
            "26.3, net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar, entityTranslucentCull",
            "26.2, net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar, entityTranslucent"})
    void realHosts(String version, String jar, String translucent) throws Exception {
        Path host = LIBRARIES.resolve(jar);
        assumeTrue(Files.isRegularFile(host), "needs " + host);
        List<String> refs;
        try (ZipFile zip = new ZipFile(host.toFile())) {
            refs = refs(transformOldCalls(version, name -> read(zip, name)));
        }
        assertTrue(refs.contains(LegacyRenderGapsBridge.RENDER_TYPES + "." + translucent + LegacyRenderGapsBridge.BY_ID), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(HELPER + ".apply(")), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(HELPER + ".register(")), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("blendEquation")), refs.toString());
    }

    private ClassNode transformOldCalls(String version, Function<String, ClassNode> host) {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = version;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        RenderBufferSynthetics.register(t);
        LegacyRenderGapsBridge.register(t, host);
        ClassNode node = new ClassNode();
        new ClassReader(t.transformClass(oldCalls(), "test/render/Gaps")).accept(node, 0);
        return node;
    }

    /** One call of each old member, the way a 1.21.1 mod made it after the 26.1 renames. */
    private static byte[] oldCalls() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/render/Gaps", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderGapsBridge.ITEM_TRANSFORM, "apply", LegacyRenderGapsBridge.OLD_APPLY, false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitLdcInsn("prefix");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderGapsBridge.TEXTURES, "register",
                "(Ljava/lang/String;" + LegacyRenderGapsBridge.DYNAMIC + ")" + LegacyRenderGapsBridge.IDENTIFIER, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyRenderGapsBridge.RENDER_TYPE, "entityTranslucentCull", LegacyRenderGapsBridge.BY_ID, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn(32775);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyRenderGapsBridge.RENDER_SYSTEM, "blendEquation", "(I)V", false);
        mv.visitTypeInsn(Opcodes.NEW, BUILDER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(256);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", "(I)V", false);
        mv.visitInsn(Opcodes.POP);
        // RenderType.debugLineStrip(width), gameRenderer.pick(partialTick), renderer.shouldRenderOffScreen(be), image.blendPixel(x, y, c)
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyRenderGapsBridge.RENDER_TYPE, "debugLineStrip", "(D)L" + LegacyRenderGapsBridge.RENDER_TYPE + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderGapsBridge.GAME_RENDERER, "pick", "(F)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LegacyRenderGapsBridge.BLOCK_ENTITY_RENDERER, "shouldRenderOffScreen",
                "(Lnet/minecraft/world/level/block/entity/BlockEntity;)Z", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderGapsBridge.NATIVE_IMAGE, "blendPixel", "(III)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, LegacyRenderGapsBridge.LEVEL_RENDERER, "ticks", "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/renderpearl/backend/opengl/GlStateManager", "getBoundFramebuffer", "()I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyRenderGapsBridge.RENDER_SYSTEM, "initGameThread", "(Z)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderGapsBridge.MONITOR, "getX", "()I", false);
        mv.visitInsn(Opcodes.POP);
        // Sheets.SIGN_MATERIALS.put(type, new Material(Sheets.SIGN_SHEET, id)), the custom wood type pattern.
        mv.visitFieldInsn(Opcodes.GETSTATIC, LegacyRenderGapsBridge.SHEETS, "SIGN_MATERIALS", "Ljava/util/Map;");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETSTATIC, LegacyRenderGapsBridge.SHEETS, "SIGN_SHEET", "Lnet/minecraft/resources/ResourceLocation;");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        // new PostChain(textures, resources, target, id).process(partialTick), then the game renderer's current effect.
        String chain = LegacyRenderGapsBridge.POST_CHAIN;
        mv.visitTypeInsn(Opcodes.NEW, chain);
        mv.visitInsn(Opcodes.DUP);
        for (int i = 0; i < 4; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, chain, "<init>", "(L" + LegacyRenderGapsBridge.TEXTURES
                + ";Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/pipeline/RenderTarget;"
                + LegacyRenderGapsBridge.IDENTIFIER + ")V", false);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, chain, "process", "(F)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyRenderGapsBridge.GAME_RENDERER, "currentEffect", "()L" + chain + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, chain, "getName", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(ZipFile zip, String name) {
        try {
            ZipEntry entry = zip.getEntry(name + ".class");
            if (entry == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> refs(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        return refs;
    }
}
