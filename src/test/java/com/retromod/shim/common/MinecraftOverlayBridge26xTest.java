/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.mapping.IntermediaryToMojangMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

/** Regression coverage for ChatBubbles' first-tick overlay check on a 26.2 client. */
class MinecraftOverlayBridge26xTest {

    private static final String OLD_MINECRAFT = "net/minecraft/class_310";
    private static final String OLD_OVERLAY = "net/minecraft/class_4071";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String OVERLAY = "net/minecraft/client/gui/screens/Overlay";
    private static final String HOP = "com/retromod/generated/MinecraftToGuiHop";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("ChatBubbles intermediary getOverlay call hops through Minecraft.gui on 26.2")
    void chatBubblesGetOverlayHopsThroughGui() {
        IntermediaryToMojangMapper.applyTo(transformer);
        Mc26_1To26_2CoreMoves.register(transformer);

        byte[] transformed = transformer.transformClass(chatBubblesOverlayCheck(), "test/ChatBubbles.class");
        MethodNode tick = method(transformed, "tick");

        boolean oldCallSurvives = false;
        MethodInsnNode hopCall = null;
        for (AbstractInsnNode instruction : tick.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(MINECRAFT) && call.name.equals("getOverlay")) {
                    oldCallSurvives = true;
                }
                if (call.owner.equals(HOP) && call.name.equals("getOverlay")) {
                    hopCall = call;
                }
            }
        }

        assertFalse(oldCallSurvives, "the removed Minecraft.getOverlay call must not survive");
        assertNotNull(hopCall, "the translated call must target the embedded receiver-hop bridge");
        assertEquals(INVOKESTATIC, hopCall.getOpcode());
        assertEquals("(L" + MINECRAFT + ";)L" + OVERLAY + ";", hopCall.desc);
    }

    @Test
    @DisplayName("overlay bridge delegates get and set to the real 26.2 Gui accessors")
    void generatedBridgeUsesGuiAccessors() {
        Mc26_1To26_2CoreMoves.register(transformer);
        byte[] synthetic = transformer.getSyntheticClasses().get(HOP);
        assertNotNull(synthetic, "the bridge must be available to the synthetic embedder");

        String overlayDescriptor = "L" + OVERLAY + ";";
        var setterRedirect = transformer.getMethodRedirects().get(
                new RetromodTransformer.MethodKey(
                        MINECRAFT, "setOverlay", "(" + overlayDescriptor + ")V"));
        assertNotNull(setterRedirect, "the removed Minecraft.setOverlay call must be redirected");
        assertEquals(HOP, setterRedirect.owner());
        assertEquals("(L" + MINECRAFT + ";" + overlayDescriptor + ")V", setterRedirect.desc());
        assertTrue(setterRedirect.devirtualize(), "the Minecraft receiver must become argument zero");

        MethodNode getOverlay = method(synthetic, "getOverlay");
        FieldInsnNode guiRead = null;
        MethodInsnNode overlayCall = null;
        for (AbstractInsnNode instruction : getOverlay.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("gui")) {
                guiRead = field;
            }
            if (instruction instanceof MethodInsnNode call && call.name.equals("overlay")) {
                overlayCall = call;
            }
        }
        assertNotNull(guiRead, "the getter must read Minecraft.gui");
        assertEquals(GETFIELD, guiRead.getOpcode());
        assertEquals(MINECRAFT, guiRead.owner);
        assertEquals("L" + GUI + ";", guiRead.desc);
        assertNotNull(overlayCall, "the getter must call Gui.overlay()");
        assertEquals(INVOKEVIRTUAL, overlayCall.getOpcode());
        assertEquals(GUI, overlayCall.owner);
        assertEquals("()L" + OVERLAY + ";", overlayCall.desc);

        MethodNode setOverlay = method(synthetic, "setOverlay");
        boolean callsSetter = false;
        for (AbstractInsnNode instruction : setOverlay.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(GUI)
                    && call.name.equals("setOverlay")
                    && call.desc.equals("(L" + OVERLAY + ";)V")) {
                callsSetter = true;
            }
        }
        assertTrue(callsSetter, "the setter must call the real Gui.setOverlay(Overlay)");
    }

    private static MethodNode method(byte[] classBytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static byte[] chatBubblesOverlayCheck() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, ACC_PUBLIC, "test/ChatBubbles", null, "java/lang/Object", null);
        MethodVisitor tick = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "tick",
                "(L" + OLD_MINECRAFT + ";)L" + OLD_OVERLAY + ";",
                null,
                null);
        tick.visitCode();
        tick.visitVarInsn(ALOAD, 0);
        tick.visitMethodInsn(
                INVOKEVIRTUAL,
                OLD_MINECRAFT,
                "method_18506",
                "()L" + OLD_OVERLAY + ";",
                false);
        tick.visitInsn(ARETURN);
        tick.visitMaxs(1, 1);
        tick.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
