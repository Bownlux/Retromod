/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #264: a 1.19.2 Forge HUD overlay draws with {@code Gui.blit(PoseStack, ...)} on the pose stack of
 * {@code RenderGuiEvent.Pre}, and a renderer adds a three-argument {@code HumanoidArmorLayer}.
 * 1.20 removed all three shapes, so the overlay died on its first frame.
 */
class LegacyGuiComponentBridgeTest {

    private static final String OVERLAY = "test/client/JumpscareOverlay";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String POSE_STACK = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String PRE = "net/minecraftforge/client/event/RenderGuiEvent$Pre";
    private static final String ARMOR = "net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer";

    private RetromodTransformer transformer;
    private String savedHost;

    @BeforeEach
    void setUp() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_19_4_to_1_20().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    /** The shape of an MCreator 1.19.2 overlay handler and armor layer construction. */
    private static byte[] overlay() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, OVERLAY, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "render", "(L" + PRE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, PRE, "getPoseStack", "()" + POSE_STACK, false);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(FCONST_0);
        mv.visitInsn(FCONST_0);
        mv.visitIntInsn(SIPUSH, 427);
        mv.visitIntInsn(SIPUSH, 240);
        mv.visitIntInsn(SIPUSH, 427);
        mv.visitIntInsn(SIPUSH, 240);
        mv.visitMethodInsn(INVOKESTATIC, GUI, "blit", "(" + POSE_STACK + "IIFFIIII)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        String parent = "Lnet/minecraft/client/renderer/entity/RenderLayerParent;";
        String model = "Lnet/minecraft/client/model/HumanoidModel;";
        MethodVisitor layer = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "layer",
                "(" + parent + model + model + ")Ljava/lang/Object;", null, null);
        layer.visitCode();
        layer.visitTypeInsn(NEW, ARMOR);
        layer.visitInsn(DUP);
        layer.visitVarInsn(ALOAD, 0);
        layer.visitVarInsn(ALOAD, 1);
        layer.visitVarInsn(ALOAD, 2);
        layer.visitMethodInsn(INVOKESPECIAL, ARMOR, "<init>", "(" + parent + model + model + ")V", false);
        layer.visitInsn(ARETURN);
        layer.visitMaxs(0, 0);
        layer.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes, String method) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        MethodNode m = cn.methods.stream().filter(x -> x.name.equals(method)).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    @Test
    @DisplayName("the overlay's pose stack and blit go to the generated helper")
    void overlayDrawsThroughTheBridge() {
        List<String> calls = calls(transformer.transformClass(overlay(), OVERLAY), "render");
        assertTrue(calls.contains(LegacyGuiComponentBridge.GENERATED + ".pose"
                        + "(Lnet/minecraftforge/client/event/RenderGuiEvent;)" + POSE_STACK),
                "getPoseStack() has to read the event's GuiGraphics: " + calls);
        assertTrue(calls.contains(LegacyGuiComponentBridge.GENERATED + ".blit" + LegacyGuiComponentBridge.BLIT_UV_DESC),
                "Gui.blit(PoseStack, ...) is gone in 1.20 and has to be redrawn: " + calls);
        assertFalse(calls.stream().anyMatch(c -> c.startsWith(GUI + ".")), calls.toString());
    }

    /**
     * {@code class WorkbenchScreen extends <base>} whose {@code renderBg} calls {@code this.blit(...)}
     * (the instance overload) and {@code blit(...)} (a static overload) the way javac compiles them
     * inside a screen: both name the screen itself as the owner.
     */
    private static byte[] screen(String name, String base) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, base, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "renderBg", "(" + POSE_STACK + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        for (int i = 0; i < 6; i++) mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, name, "blit", LegacyGuiComponentBridge.BLIT_INSTANCE_DESC, false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(FCONST_0);
        mv.visitInsn(FCONST_0);
        for (int i = 0; i < 4; i++) mv.visitIntInsn(SIPUSH, 176);
        mv.visitMethodInsn(INVOKESTATIC, name, "blit", LegacyGuiComponentBridge.BLIT_UV_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("a container screen's own this.blit(...) and blit(...) calls are redrawn")
    void screenSubclassCallsDrawThroughTheBridge() {
        String screen = "test/client/WorkbenchScreen";
        byte[] original = screen(screen, "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen");
        transformer.setJarClassBytesProvider(name -> screen.equals(name) ? original : null);

        List<String> calls = calls(transformer.transformClass(original, screen), "renderBg");
        assertTrue(calls.contains(LegacyGuiComponentBridge.GENERATED + ".blitInstance(Ljava/lang/Object;"
                        + LegacyGuiComponentBridge.BLIT_INSTANCE_DESC.substring(1)),
                "this.blit(...) on the 256x256 sheet has to pass the screen as an ignored argument: " + calls);
        assertTrue(calls.contains(LegacyGuiComponentBridge.GENERATED + ".blit" + LegacyGuiComponentBridge.BLIT_UV_DESC),
                "an inherited static blit(...) has to be redrawn: " + calls);
        assertFalse(calls.stream().anyMatch(c -> c.startsWith(screen + ".")), calls.toString());
    }

    @Test
    @DisplayName("a class outside the GUI hierarchy keeps its own blit methods")
    void unrelatedBlitIsLeftAlone() {
        String helper = "test/client/DrawHelper";
        byte[] original = screen(helper, "java/lang/Object");
        transformer.setJarClassBytesProvider(name -> helper.equals(name) ? original : null);

        List<String> calls = calls(transformer.transformClass(original, helper), "renderBg");
        assertEquals(2, calls.stream().filter(c -> c.startsWith(helper + ".blit")).count(), calls.toString());
    }

    @Test
    @DisplayName("a three-argument armor layer gets the client's model manager")
    void armorLayerGetsModelManager() {
        List<String> calls = calls(transformer.transformClass(overlay(), OVERLAY), "layer");
        assertTrue(calls.contains(LegacyGuiComponentBridge.GENERATED + ".armorLayer"
                + "(Lnet/minecraft/client/renderer/entity/RenderLayerParent;"
                + "Lnet/minecraft/client/model/HumanoidModel;Lnet/minecraft/client/model/HumanoidModel;)L"
                + ARMOR + ";"), calls.toString());
    }

    @Test
    @DisplayName("a mod's own armor layer subclass passes the model manager to its super call")
    void armorLayerSubclassGetsModelManager() {
        String parent = "Lnet/minecraft/client/renderer/entity/RenderLayerParent;";
        String model = "Lnet/minecraft/client/model/HumanoidModel;";
        String oldDesc = "(" + parent + model + model + ")V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/client/RobeLayer", null, ARMOR, null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", oldDesc, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitVarInsn(ALOAD, 3);
        ctor.visitMethodInsn(INVOKESPECIAL, ARMOR, "<init>", oldDesc, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(transformer.transformClass(cw.toByteArray(), "test/client/RobeLayer"), "<init>");
        assertTrue(calls.contains("net/minecraft/client/Minecraft.getModelManager()"
                + "Lnet/minecraft/client/resources/model/ModelManager;"), calls.toString());
        assertTrue(calls.contains(ARMOR + ".<init>" + LegacyGuiComponentBridge.ARMOR_LAYER_DESC),
                "the super call must use the 1.20 constructor: " + calls);
    }

    @Test
    @DisplayName("the generated helper is well formed and draws with the old texture coordinates")
    void generatedHelperIsWellFormed() {
        byte[] helper = transformer.getSyntheticClasses().get(LegacyGuiComponentBridge.GENERATED);
        assertNotNull(helper, "the 1.20 shim must register the helper on a 1.20.1 host");
        ClassNode cn = new ClassNode();
        new ClassReader(helper).accept(cn, 0);
        // BasicVerifier checks stack and local types without loading Minecraft classes.
        for (MethodNode method : cn.methods) {
            assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(cn.name, method),
                    "the helper's " + method.name + method.desc + " must verify");
        }

        List<String> quad = calls(helper, "quad");
        assertTrue(quad.contains("com/mojang/blaze3d/vertex/BufferUploader.drawWithShader"
                + "(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V"), quad.toString());
        assertEquals(4, quad.stream().filter(c -> c.contains("VertexConsumer.endVertex")).count(),
                "one quad is four vertices");
    }

    @Test
    @DisplayName("hosts from 1.21 on, whose buffer API changed, do not get the bridge")
    void newerHostsAreLeftAlone() {
        assertTrue(LegacyGuiComponentBridge.supportsHost("1.20.1"));
        assertTrue(LegacyGuiComponentBridge.supportsHost("1.20.4"));
        assertFalse(LegacyGuiComponentBridge.supportsHost("1.21.1"));
        assertFalse(LegacyGuiComponentBridge.supportsHost("26.1"));
        assertFalse(LegacyGuiComponentBridge.supportsHost("1.19.4"));
    }
}
