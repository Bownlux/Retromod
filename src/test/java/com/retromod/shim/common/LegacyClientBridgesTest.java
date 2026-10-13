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
