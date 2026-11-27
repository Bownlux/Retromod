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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Pre-1.20 client code on 26.1 and newer: an MCreator biped's renderer and armor layer
 * (Pyrologer and Friends, 1.19.2), and HUD drawing with a {@code PoseStack}.
 */
class LegacyClientBridgesTest {

    private static final String POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old armor layer constructor builds the current layer through the generated factory")
    void armorLayerIsBuilt() throws Exception {
        RetromodTransformer transformer = fresh();
        LegacyArmorLayerBridge.registerLayerRedirects(transformer);
        String layer = "net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer";
        String model = "Lnet/minecraft/client/model/HumanoidModel;";
        byte[] renderer = method("mod/BoneriserRenderer", "addArmor",
                "(Lnet/minecraft/client/renderer/entity/RenderLayerParent;" + model + model + ")V", mv -> {
                    mv.visitTypeInsn(NEW, layer);
                    mv.visitInsn(DUP);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitMethodInsn(INVOKESPECIAL, layer, "<init>",
                            "(Lnet/minecraft/client/renderer/entity/RenderLayerParent;" + model + model + ")V", false);
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                });

        assertTrue(calls(read(transformer.transformClass(renderer, "mod/BoneriserRenderer.class")))
                .contains(LegacyArmorLayerBridge.CALLS + ".armorLayer"));
        ClassNode calls = read(LegacyArmorLayerBridge.generateCalls());
        for (MethodNode method : calls.methods) new Analyzer<>(new BasicVerifier()).analyze(calls.name, method);
    }

    @Test
    @DisplayName("an old biped renderer gets the render state and texture methods 26.1 made abstract")
    void bipedRendererGetsStateMethods() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/BoneriserRenderer", null,
                "net/minecraft/client/renderer/entity/HumanoidMobRenderer", null);
        MethodVisitor texture = cw.visitMethod(ACC_PUBLIC, "getTextureLocation",
                "(Lmod/Boneriser;)Lnet/minecraft/resources/Identifier;", null, null);
        texture.visitCode();
        texture.visitInsn(ACONST_NULL);
        texture.visitInsn(ARETURN);
        texture.visitMaxs(1, 2);
        texture.visitEnd();
        cw.visitEnd();

        ClassNode out = read(LegacyLivingRendererAdapter.apply(cw.toByteArray()));
        MethodNode create = out.methods.stream().filter(m -> m.name.equals("createRenderState")).findFirst().orElseThrow();
        assertTrue(calls(create).contains("net/minecraft/client/renderer/entity/state/HumanoidRenderState.<init>"),
                "a biped's base reads a humanoid state");
        assertTrue(out.methods.stream().anyMatch(m -> m.name.equals("getTextureLocation")
                && m.desc.equals("(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Lnet/minecraft/resources/Identifier;")));
    }

    @Test
    @DisplayName("an old HUD handler's pose stack and fill reach the drawing helper")
    void hudDrawingIsBridged() {
        RetromodTransformer transformer = fresh();
        LegacyPoseGuiBridge.registerRedirects(transformer, null);
        String event = "net/neoforged/neoforge/client/event/RenderGuiLayerEvent$Post";
        byte[] handler = method("mod/Hud", "onRender", "(L" + event + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, event, "getPoseStack", "()" + POSE, false);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ICONST_0);
            mv.visitIntInsn(BIPUSH, 10);
            mv.visitIntInsn(BIPUSH, 10);
            mv.visitInsn(ICONST_M1);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/gui/GuiComponent", "fill", "(" + POSE + "IIIII)V", false);
            mv.visitInsn(RETURN);
        });

        List<String> calls = calls(read(transformer.transformClass(handler, "mod/Hud.class")));
        assertTrue(calls.contains(LegacyPoseGuiBridge.HELPER + ".poseFor"), "saw " + calls);
        assertTrue(calls.contains(LegacyPoseGuiBridge.HELPER + ".fill"), "saw " + calls);
    }

    @Test
    @DisplayName("a 1.16 texture bind and tint feed the next old GUI blit, and a 1.20 mod's bindForSetup stays its own")
    void oldBindAndTintReachTheHelper() {
        String manager = "net/minecraft/client/renderer/texture/TextureManager";
        String id = "Lnet/minecraft/resources/ResourceLocation;";
        byte[] binder = method("mod/Gui", "draw", "(L" + manager + ";" + id + ")V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, manager, "bind", "(" + id + ")V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, manager, "bindForSetup", "(" + id + ")V", false);
            for (String[] tint : new String[][] {{"com/mojang/blaze3d/systems/RenderSystem", "color4f"},
                    {"com/mojang/blaze3d/platform/GlStateManager", "_color4f"}}) {
                for (int i = 0; i < 4; i++) mv.visitInsn(FCONST_1);
                mv.visitMethodInsn(INVOKESTATIC, tint[0], tint[1], "(FFFF)V", false);
            }
            mv.visitInsn(RETURN);
        });
        RetromodTransformer transformer = fresh();
        LegacyPoseGuiBridge.registerOldBinds(transformer, null, null);

        transformer.setModSourceVersion("1.16.5");
        List<String> old = calls(read(transformer.transformClass(binder, "mod/Gui.class")));
        transformer.setModSourceVersion("1.20.1");
        List<String> newer = calls(read(transformer.transformClass(binder, "mod/Gui.class")));
        transformer.setModSourceVersion(null);

        assertEquals(2, old.stream().filter((LegacyPoseGuiBridge.HELPER + ".bindTexture")::equals).count(), "saw " + old);
        assertEquals(2, old.stream().filter((LegacyPoseGuiBridge.HELPER + ".setShaderColor")::equals).count(), "saw " + old);
        assertTrue(newer.contains(manager + ".bindForSetup"),
                "from 1.20 GUI code names its texture in the blit, so bindForSetup is renderer setup, saw " + newer);
        assertTrue(newer.contains(LegacyPoseGuiBridge.HELPER + ".bindTexture"), "bind is 1.16 only and still bridged, saw " + newer);

        // A host that still has the methods keeps every call.
        ClassNode host = new ClassNode();
        host.methods.add(new MethodNode(ACC_PUBLIC, "bind", "(" + id + ")V", null, null));
        host.methods.add(new MethodNode(ACC_PUBLIC, "bindForSetup", "(" + id + ")V", null, null));
        ClassNode system = new ClassNode();
        system.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, "color4f", "(FFFF)V", null, null));
        transformer = fresh();
        LegacyPoseGuiBridge.registerOldBinds(transformer, system, host);
        List<String> kept = calls(read(transformer.transformClass(binder, "mod/Gui.class")));
        assertFalse(kept.contains(LegacyPoseGuiBridge.HELPER + ".bindTexture"), "saw " + kept);
        assertFalse(kept.contains(LegacyPoseGuiBridge.HELPER + ".setShaderColor"), "saw " + kept);
    }

    @Test
    @DisplayName("a Fabric mod's Font.draw, which the 26.x name table calls drawInBatch, reaches the drawing helper")
    void fontDrawUnderItsHostNameIsBridged() {
        RetromodTransformer transformer = fresh();
        LegacyPoseGuiBridge.registerRedirects(transformer, null);
        String font = "net/minecraft/client/gui/Font";
        byte[] handler = method("mod/Hud", "label", "(L" + font + ";" + POSE + ")V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitLdcInsn("hi");
            mv.visitInsn(FCONST_0);
            mv.visitInsn(FCONST_0);
            mv.visitInsn(ICONST_M1);
            mv.visitMethodInsn(INVOKEVIRTUAL, font, "drawInBatch", "(" + POSE + "Ljava/lang/String;FFI)I", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });
        List<String> calls = calls(read(transformer.transformClass(handler, "mod/Hud.class")));
        assertTrue(calls.contains(LegacyPoseGuiBridge.HELPER + ".fontDraw"), "saw " + calls);
    }

    @Test
    @DisplayName("the old six-argument Button constructor builds through the builder on Fabric, Forge and NeoForge")
    void oldButtonConstructorOnEveryLoader() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        String button = "net/minecraft/client/gui/components/Button";
        String component = "Lnet/minecraft/network/chat/Component;";
        String press = "L" + button + "$OnPress;";
        byte[] screen = method("mod/Menu", "init", "()Ljava/lang/Object;", mv -> {
            mv.visitTypeInsn(NEW, button);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(ICONST_2);
            mv.visitInsn(ICONST_3);
            mv.visitInsn(ICONST_4);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, button, "<init>", "(IIII" + component + press + ")V", false);
            mv.visitInsn(ARETURN);
        });
        List<Runnable> loaders = List.of(
                () -> new com.retromod.shim.fabric.Fabric_1_21_11_to_26_1().registerRedirects(RetromodTransformer.getInstance()),
                () -> new com.retromod.shim.forge.Forge_1_21_11_to_26_1().registerRedirects(RetromodTransformer.getInstance()),
                () -> new com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1().registerRedirects(RetromodTransformer.getInstance()));
        for (Runnable register : loaders) {
            RetromodTransformer transformer = fresh();
            register.run();
            List<String> calls = calls(read(transformer.transformClass(screen, "mod/Menu.class")));
            assertTrue(calls.contains(LegacyPoseScreenAdapter.HELPER + ".button"), "saw " + calls);
            assertFalse(calls.contains(button + ".<init>"), "saw " + calls);
        }
    }

    private static RetromodTransformer fresh() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        return transformer;
    }

    private static byte[] method(String owner, String name, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) calls.addAll(calls(method));
        return calls;
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }
}
