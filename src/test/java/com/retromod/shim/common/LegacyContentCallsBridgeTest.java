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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The removed members big content mods call reach the rebuilt helpers on a 26.3-shaped host, and stay where the host has them. */
class LegacyContentCallsBridgeTest {


    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("every content call goes to its helper on a host without the old members")
    void contentCallsAreBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyContentCallsBridge.register(t, name -> new ClassNode());

        List<String> calls = calls(t.transformClass(oldCalls(), "test/content/Old"));

        String world = LegacyContentCallsBridge.WORLD + ".";
        String screens = LegacyContentCallsBridge.SCREENS + ".";
        for (String expected : new String[]{world + "getPrecipitationAt", world + "recipeHolder", world + "shaped", world + "shapeless",
                LegacyContentCallsBridge.PARTICLES + ".itemParticle", LegacyContentCallsBridge.SIGNS + ".getFrontText",
                screens + "init", screens + "isCopy", screens + "copyFrom", screens + "componentStyleAtWidth"}) {
            assertTrue(calls.stream().anyMatch(c -> c.startsWith(expected + "(")), expected + " missing from " + calls);
        }
    }

    @Test
    @DisplayName("a host that still has a member keeps the mod's call, as 26.1 keeps getFrontText and 1.21.11 the item stack particle")
    void hostKeepsWhatItHas() {
        ClassNode sign = new ClassNode();
        sign.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getFrontText", "()" + LegacyContentCallsBridge.SIGN_TEXT, null, null));
        ClassNode particle = new ClassNode();
        particle.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/item/ItemStack;)V", null, null));
        Map<String, ClassNode> host = Map.of(LegacyContentCallsBridge.SIGN, sign, LegacyContentCallsBridge.PARTICLE, particle);
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyContentCallsBridge.register(t, name -> host.getOrDefault(name, new ClassNode()));

        List<String> calls = calls(t.transformClass(oldCalls(), "test/content/Old"));

        assertTrue(calls.contains(LegacyContentCallsBridge.SIGN + ".getFrontText()" + LegacyContentCallsBridge.SIGN_TEXT), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(LegacyContentCallsBridge.PARTICLE + ".<init>")), calls.toString());
    }

    @Test
    @DisplayName("with the server API bridge registered too, sign text, precipitation and item particles resolve to these helpers")
    void sharedMembersResolveHere() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyServerApiBridge.register(t, name -> new ClassNode());
        LegacyContentCallsBridge.register(t, name -> new ClassNode());

        List<String> calls = calls(t.transformClass(oldCalls(), "test/content/Old"));

        assertTrue(calls.stream().anyMatch(c -> c.startsWith(LegacyContentCallsBridge.SIGNS + ".getFrontText(")), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(LegacyContentCallsBridge.WORLD + ".getPrecipitationAt(")), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(LegacyContentCallsBridge.PARTICLES + ".itemParticle(")), calls.toString());
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("com/retromod/generated/")), "no server API helper may take them: " + calls);
    }

    private static byte[] oldCalls() {
        String pos = LegacyContentCallsBridge.POS;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/content/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        nulls(mv, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyContentCallsBridge.BIOME, "getPrecipitationAt",
                "(" + pos + ")Lnet/minecraft/world/level/biome/Biome$Precipitation;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyContentCallsBridge.HOLDER);
        mv.visitInsn(Opcodes.DUP);
        nulls(mv, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyContentCallsBridge.HOLDER, "<init>",
                "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/world/item/crafting/Recipe;)V", false);
        mv.visitInsn(Opcodes.POP);
        nulls(mv, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyContentCallsBridge.SHAPED, "shaped",
                "(" + LegacyContentCallsBridge.CATEGORY + LegacyContentCallsBridge.ITEM_LIKE + ")L" + LegacyContentCallsBridge.SHAPED + ";", false);
        mv.visitInsn(Opcodes.POP);
        nulls(mv, 2);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyContentCallsBridge.SHAPELESS, "shapeless",
                "(" + LegacyContentCallsBridge.CATEGORY + LegacyContentCallsBridge.ITEM_LIKE + "I)L" + LegacyContentCallsBridge.SHAPELESS + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyContentCallsBridge.PARTICLE);
        mv.visitInsn(Opcodes.DUP);
        nulls(mv, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyContentCallsBridge.PARTICLE, "<init>",
                "(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/item/ItemStack;)V", false);
        mv.visitInsn(Opcodes.POP);
        nulls(mv, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyContentCallsBridge.SIGN, "getFrontText", "()" + LegacyContentCallsBridge.SIGN_TEXT, false);
        mv.visitInsn(Opcodes.POP);
        nulls(mv, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyContentCallsBridge.SCREEN, "init", "(Lnet/minecraft/client/Minecraft;II)V", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 67);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyContentCallsBridge.SCREEN, "isCopy", "(I)Z", false);
        mv.visitInsn(Opcodes.POP);
        nulls(mv, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyContentCallsBridge.MODEL_PART, "copyFrom",
                "(L" + LegacyContentCallsBridge.MODEL_PART + ";)V", false);
        nulls(mv, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyContentCallsBridge.SPLITTER, "componentStyleAtWidth",
                "(Lnet/minecraft/util/FormattedCharSequence;I)" + LegacyContentCallsBridge.STYLE, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(8, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void nulls(MethodVisitor mv, int count) {
        for (int i = 0; i < count; i++) mv.visitInsn(Opcodes.ACONST_NULL);
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
