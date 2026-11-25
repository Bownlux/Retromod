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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The graphics mode option, option constructors, component input, block state loot condition and hover item info bridges. */
class LegacyOptionsGapsTest {

    private static final String OPTION = LegacyOptionsBridge.OPTION;
    private String savedVersion;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("on a 26.3 host the old option constructor and graphicsMode reach the listener-shaped helpers")
    void optionsOn263() {
        RetromodTransformer t = fresh();
        LegacyOptionsBridge.register(t, Map.of(OPTION, new ClassNode(), LegacyOptionsBridge.OPTIONS, new ClassNode())::get);

        List<String> refs = refs(t.transformClass(optionCalls(), "test/gaps/Options"));

        assertTrue(refs.contains(LegacyOptionsBridge.OPTION_INSTANCES + ".create" + LegacyOptionsBridge.SIX_ARGS + "L" + OPTION + ";"), refs.toString());
        assertTrue(refs.contains(LegacyOptionsBridge.GRAPHICS_OPTION_262 + ".graphicsMode(L" + LegacyOptionsBridge.OPTIONS + ";)L" + OPTION + ";"),
                refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.startsWith("NEW " + OPTION)), "no old constructor call is left: " + refs);
    }

    @Test
    @DisplayName("1.21.11 keeps the consumer constructor and builds the graphics mode with it")
    void optionsOn12111() {
        RetromodTransformer t = fresh();
        ClassNode option = new ClassNode();
        option.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", LegacyOptionsBridge.SIX_ARGS + "V", null, null));
        LegacyOptionsBridge.register(t, Map.of(OPTION, option, LegacyOptionsBridge.OPTIONS, new ClassNode())::get);

        List<String> refs = refs(t.transformClass(optionCalls(), "test/gaps/Options"));

        assertTrue(refs.contains(OPTION + ".<init>" + LegacyOptionsBridge.SIX_ARGS + "V"), refs.toString());
        assertTrue(refs.contains(LegacyOptionsBridge.GRAPHICS_OPTION + ".graphicsMode(L" + LegacyOptionsBridge.OPTIONS + ";)L" + OPTION + ";"),
                refs.toString());
    }

    @Test
    @DisplayName("component input, the block state loot condition and the hover item info take their current shapes on 26.3")
    void gameDataShapesOn263() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = fresh();
        LegacyGameDataShapesBridge.register(t, name -> null);

        List<String> refs = refs(t.transformClass(dataCalls(), "test/gaps/Data"));

        assertTrue(refs.contains(LegacyGameDataShapesBridge.COMPONENT_GETTER + ".get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"),
                refs.toString());
        assertTrue(refs.contains(LegacyGameDataShapesBridge.CONDITION_BUILDER + ".hasBlockStateProperties(Lnet/minecraft/world/level/block/Block;)L"
                + LegacyGameDataShapesBridge.CONDITION_BUILDER + ";"), refs.toString());
        assertTrue(refs.contains("NEW " + LegacyGameDataShapesBridge.LEGACY_ITEM_STACK_INFO), refs.toString());
    }

    @Test
    @DisplayName("1.21.11 keeps the block state loot condition, and the hover item info is rebuilt there too")
    void gameDataShapesOn12111() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer t = fresh();
        LegacyGameDataShapesBridge.register(t, Map.of(LegacyGameDataShapesBridge.STATE_CONDITION, new ClassNode())::get);

        List<String> refs = refs(t.transformClass(dataCalls(), "test/gaps/Data"));

        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyGameDataShapesBridge.STATE_CONDITION + ".hasBlockStateProperties")), refs.toString());
        assertTrue(refs.contains("NEW " + LegacyGameDataShapesBridge.LEGACY_ITEM_STACK_INFO), "1.21.11 lacks ItemStackInfo too: " + refs);
    }

    @Test
    @DisplayName("the old graphics mode numbering goes to the preset helpers")
    void graphicsStatusIdsAreBridged() {
        RetromodTransformer t = fresh();
        LegacyOptionsBridge.register(t, name -> null);
        String presets = RebuiltApiClasses.ROOT + "minecraft/client/LegacyGraphicsPresets";
        var redirects = t.getMethodRedirects();
        assertTrue(redirects.keySet().stream().anyMatch(k -> k.owner().equals(LegacyOptionsBridge.GRAPHICS_STATUS) && k.name().equals("getId")
                && redirects.get(k).owner().equals(presets)), redirects.keySet().toString());
        assertTrue(redirects.keySet().stream().anyMatch(k -> k.owner().equals(LegacyOptionsBridge.GRAPHICS_PRESET) && k.name().equals("byId")
                && redirects.get(k).owner().equals(presets)), redirects.keySet().toString());
    }

    @Test
    @DisplayName("a pre-1.19 read and write of the graphics mode field reach the preset")
    void graphicsModeFieldReachesThePreset() {
        RetromodTransformer t = fresh();
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyOptionsBridge.register(t, name -> null);
        String status = "L" + LegacyOptionsBridge.GRAPHICS_STATUS + ";";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Mode", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, LegacyOptionsBridge.OPTIONS, "graphicsMode", status);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, LegacyOptionsBridge.OPTIONS, "graphicsMode", status);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        writer.visitEnd();

        List<String> refs = refs(t.transformClass(writer.toByteArray(), "test/gaps/Mode"));
        String presets = RebuiltApiClasses.ROOT + "minecraft/client/LegacyGraphicsPresets";
        String preset = "L" + LegacyOptionsBridge.GRAPHICS_PRESET + ";";
        assertTrue(refs.contains(presets + ".closest(L" + LegacyOptionsBridge.OPTIONS + ";)" + preset), refs.toString());
        assertTrue(refs.contains(presets + ".apply(L" + LegacyOptionsBridge.OPTIONS + ";" + preset + ")V"), refs.toString());
    }

    private static RetromodTransformer fresh() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        return t;
    }

    private static byte[] optionCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Options", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, OPTION);
        mv.visitInsn(Opcodes.DUP);
        for (int i = 0; i < 6; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OPTION, "<init>", LegacyOptionsBridge.SIX_ARGS + "V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyOptionsBridge.OPTIONS, "graphicsMode", LegacyOptionsBridge.GRAPHICS_MODE, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(8, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] dataCalls() {
        String block = "Lnet/minecraft/world/level/block/Block;";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Data", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LegacyGameDataShapesBridge.COMPONENT_INPUT, "get",
                "(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyGameDataShapesBridge.STATE_CONDITION, "hasBlockStateProperties",
                "(" + block + ")L" + LegacyGameDataShapesBridge.STATE_CONDITION + "$Builder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyGameDataShapesBridge.ITEM_STACK_INFO);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyGameDataShapesBridge.ITEM_STACK_INFO, "<init>", "(Lnet/minecraft/world/item/ItemStack;)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) refs.add("NEW " + type.desc);
            }
        }
        return refs;
    }
}
