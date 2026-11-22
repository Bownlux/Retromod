/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The second round of single removed members reaches the host's new name or a rebuilt helper. */
class LegacySmallCallsRoundTwoTest {

    private static final String EXTRACT = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("sounds, cycle buttons, user types, minecarts, tooltips and the level context matrix are bridged on a 26.3 host")
    void secondRoundIsBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ClassNode screen = withMethod("extractRenderStateWithTooltipAndSubtitles", EXTRACT);
        Map<String, ClassNode> host = Map.of(LegacySmallCallsBridge.SCREEN, screen, LegacySmallCallsBridge.LEVEL_CONTEXT, new ClassNode(),
                LegacySmallCallsBridge.SOUND_EVENTS, new ClassNode());
        LegacySmallCallsBridge.registerSecondRound(t, host::get);

        List<String> refs = refs(t.transformClass(oldCalls(), "test/two/Old"));

        assertTrue(refs.contains("F " + LegacySmallCallsBridge.SOUND_EVENTS + ".LEAD_UNTIED"), "a knot breaking unties the lead: " + refs);
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.CYCLE_BUTTONS + ".booleanBuilder(")), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.USER_TYPES + ".getType(L" + LegacySmallCallsBridge.USER + ";)L"
                + LegacySmallCallsBridge.USER_TYPES + ";"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.MINECARTS + ".chest(")), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.SCREEN + ".extractRenderStateWithTooltipAndSubtitles" + EXTRACT), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.CONTEXT_MATRICES + ".positionMatrix(")), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("LEASH_KNOT") || r.contains("renderWithTooltip(")), refs.toString());
    }

    @Test
    @DisplayName("each host keeps what it still has, and 1.21.11 renders tooltips under its own name")
    void hostGates() {
        ClassNode oldScreen = withMethod("renderWithTooltipAndSubtitles", "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V");
        assertEquals("renderWithTooltipAndSubtitles", LegacySmallCallsBridge.tooltipTarget(oldScreen)[0]);
        assertEquals("extractRenderStateWithTooltipAndSubtitles",
                LegacySmallCallsBridge.tooltipTarget(withMethod("extractRenderStateWithTooltipAndSubtitles", EXTRACT))[0]);

        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ClassNode sounds = new ClassNode();
        sounds.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "LEASH_KNOT_BREAK",
                "Lnet/minecraft/sounds/SoundEvent;", null, null));
        LegacySmallCallsBridge.registerSecondRound(t, Map.of(LegacySmallCallsBridge.SOUND_EVENTS, sounds)::get);
        assertTrue(refs(t.transformClass(oldCalls(), "test/two/Old")).contains("F " + LegacySmallCallsBridge.SOUND_EVENTS
                + ".LEASH_KNOT_BREAK"), "a host that still has the old sound keeps it");
    }

    private static byte[] oldCalls() {
        String component = "Lnet/minecraft/network/chat/Component;";
        String cycle = LegacySmallCallsBridge.CYCLE_BUTTON;
        String cart = LegacySmallCallsBridge.MINECART + "MinecartChest";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/two/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, LegacySmallCallsBridge.SOUND_EVENTS, "LEASH_KNOT_BREAK", "Lnet/minecraft/sounds/SoundEvent;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, cycle, "booleanBuilder", "(" + component + component + ")L" + cycle + "$Builder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.USER, "getType", "()L" + LegacySmallCallsBridge.USER + "$Type;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, cart);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, cart, "<init>", "(Lnet/minecraft/world/level/Level;DDD)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.SCREEN, "renderWithTooltip", EXTRACT, false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LegacySmallCallsBridge.LEVEL_CONTEXT, "positionMatrix", "()Lorg/joml/Matrix4f;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(12, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode withMethod(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add("F " + field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
