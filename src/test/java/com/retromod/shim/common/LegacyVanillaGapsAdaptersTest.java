/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.retromod.shim.common.LegacyVanillaGapsAdapters.*;
import static org.junit.jupiter.api.Assertions.*;

/** An old furnace's burn time override and old GUI render methods take the shapes 26.x needs. */
class LegacyVanillaGapsAdaptersTest {

    private static final String OVEN = "test/gaps/Oven";
    private static final String WIDGET = "test/gaps/Widget";

    @Test
    @DisplayName("on 1.21.2 to 26.2 hosts an old oven gets the fuel values form, and its super call asks the host with the level's fuel")
    void furnaceTakesTheFuelValuesForm() throws AnalyzerException {
        ClassNode node = node(apply(oven(), FURNACE_FUEL_FLAG, "1.20.1"));

        MethodNode added = method(node, "getBurnDuration", FUEL_BURN);
        assertNotNull(added, "the host calls this form");
        assertTrue(calls(added).contains(OVEN + ".getBurnDuration" + OLD_BURN), "it runs the old override");
        List<String> handlers = added.tryCatchBlocks.stream().map(block -> block.type).toList();
        assertEquals(List.of("java/lang/LinkageError", "java/lang/RuntimeException"), handlers, "a failing override falls back to the host");
        List<String> old = calls(method(node, "getBurnDuration", OLD_BURN));
        assertTrue(old.contains(OVEN + "." + SUPER_BURN + OLD_BURN), old.toString());
        List<String> superBurn = calls(method(node, SUPER_BURN, OLD_BURN));
        assertTrue(superBurn.contains(FURNACE + ".getBurnDuration" + FUEL_BURN), superBurn.toString());
        assertTrue(superBurn.contains(LegacyVanillaGapsBridge.FUELS + ".fuelValues(Ljava/lang/Object;)Lnet/minecraft/world/level/block/entity/FuelValues;"),
                superBurn.toString());
        verify(node);
    }

    @Test
    @DisplayName("on 26.3 the same oven gets the server level form")
    void furnaceTakesTheServerLevelForm() throws AnalyzerException {
        ClassNode node = node(apply(oven(), FURNACE_LEVEL_FLAG, "1.21.1"));

        assertNotNull(method(node, "getBurnDuration", LEVEL_BURN));
        MethodNode superBurn = method(node, SUPER_BURN, OLD_BURN);
        assertTrue(calls(superBurn).contains(FURNACE + ".getBurnDuration" + LEVEL_BURN), calls(superBurn).toString());
        assertTrue(java.util.stream.StreamSupport.stream(superBurn.instructions.spliterator(), false)
                .anyMatch(insn -> insn.getOpcode() == Opcodes.INSTANCEOF), "a client furnace is not cast to a server level");
        verify(node);
    }

    @Test
    @DisplayName("a furnace that extends the host's smoker gets the same forms, its super call included")
    void hostFurnaceSubclassesAreCovered() throws AnalyzerException {
        String smoker = "net/minecraft/world/level/block/entity/SmokerBlockEntity";
        ClassNode node = node(apply(oven(smoker), FURNACE_LEVEL_FLAG, "1.20.1"));

        assertNotNull(method(node, "getBurnDuration", LEVEL_BURN));
        assertTrue(calls(method(node, "getBurnDuration", OLD_BURN)).contains(OVEN + "." + SUPER_BURN + OLD_BURN));
        verify(node);
    }

    @Test
    @DisplayName("a furnace built for 1.21.2 or newer already has the new form and is left alone")
    void newerFurnacesAreLeftAlone() {
        byte[] bytes = oven();
        assertSame(bytes, apply(bytes, FURNACE_FUEL_FLAG, "1.21.2"));
    }

    @Test
    @DisplayName("an old render method hands its GUI frame to the client helper before it draws")
    void renderMethodsShareTheirFrame() throws AnalyzerException {
        ClassNode node = node(apply(widget(), GUI_FRAME_FLAG, "1.20.1"));

        List<String> calls = calls(method(node, "render", "(" + GRAPHICS + "IIF)V"));
        assertEquals(LegacyVanillaGapsBridge.FRAMES + ".frame(Ljava/lang/Object;)V", calls.get(0), calls.toString());
        verify(node);
    }

    @Test
    @DisplayName("a mod built for 1.21.6 sets its tooltips on the frame itself, so its methods are left alone")
    void newerRenderMethodsAreLeftAlone() {
        byte[] bytes = widget();
        assertSame(bytes, apply(bytes, GUI_FRAME_FLAG, "1.21.6"));
    }

    @Test
    @DisplayName("a class that builds an attack packet sends it through the helper; other classes keep their sends")
    void attackSendsGoThroughTheHelper() throws AnalyzerException {
        ClassNode node = node(apply(attacker(true), ATTACK_FLAG, "1.21.1"));

        List<String> calls = calls(method(node, "attack", "(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/world/entity/Entity;)V"));
        assertTrue(calls.contains(LegacyVanillaGapsBridge.CLIENT + ".send(Ljava/lang/Object;Ljava/lang/Object;)V"), calls.toString());
        verify(node);
        byte[] plain = attacker(false);
        assertSame(plain, apply(plain, ATTACK_FLAG, "1.21.1"));
    }

    private static byte[] apply(byte[] bytes, String flag, String modVersion) {
        return LegacyVanillaGapsAdapters.apply(bytes, Set.of(flag)::contains, modVersion);
    }

    /** handcrafted's oven: an old burn time override that halves the furnace's own. */
    private static byte[] oven() {
        return oven(FURNACE);
    }

    private static byte[] oven(String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OVEN, null, superName, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PROTECTED, "getBurnDuration", OLD_BURN, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "getBurnDuration", OLD_BURN, false);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IDIV);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A widget whose render method draws through the GUI frame it is handed. */
    private static byte[] widget() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, WIDGET, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + GRAPHICS + "IIF)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/gui/GuiGraphicsExtractor", "fill", "(IIIII)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A client combat helper: it builds an attack packet, or only sends a packet it was given, and sends it. */
    private static byte[] attacker(boolean builds) {
        String listener = "net/minecraft/client/multiplayer/ClientPacketListener";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/Attacker", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "attack",
                "(L" + listener + ";Lnet/minecraft/world/entity/Entity;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (builds) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyVanillaGapsBridge.WORLD, "createAttackPacket",
                    "(Lnet/minecraft/world/entity/Entity;Z)Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;", false);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, listener, "send", SEND, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Each method's stack and locals stay consistent. */
    private static void verify(ClassNode node) throws AnalyzerException {
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }
}
