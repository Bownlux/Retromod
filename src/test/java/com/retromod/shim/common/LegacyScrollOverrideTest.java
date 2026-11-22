/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 screen's three-argument mouseScrolled keeps running on a host that passes a horizontal amount too. */
class LegacyScrollOverrideTest {

    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("the old override gets the four-argument form and its super call passes no horizontal amount")
    void oldScrollIsBridged() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/ListScreen", null, SCREEN, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "mouseScrolled", LegacyGuiOverrideAdapter.OLD_SCROLL, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.DLOAD, 1);
        mv.visitVarInsn(Opcodes.DLOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SCREEN, "mouseScrolled", LegacyGuiOverrideAdapter.OLD_SCROLL, false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(LegacyGuiOverrideAdapter.apply(cw.toByteArray())).accept(node, 0);

        MethodNode bridge = node.methods.stream().filter(m -> m.desc.equals(LegacyGuiOverrideAdapter.NEW_SCROLL)).findFirst().orElse(null);
        assertNotNull(bridge, "the host calls the four-argument scroll");
        MethodNode old = node.methods.stream().filter(m -> m.desc.equals(LegacyGuiOverrideAdapter.OLD_SCROLL)).findFirst().orElseThrow();
        MethodInsnNode superCall = null;
        for (var insn : old.instructions) if (insn instanceof MethodInsnNode call) superCall = call;
        assertEquals(LegacyGuiOverrideAdapter.NEW_SCROLL, superCall.desc, "the super call reaches the host's scroll");
        for (MethodNode method : node.methods) new Analyzer<>(new BasicInterpreter()).analyze(node.name, method);
    }

    @Test
    @DisplayName("a screen mixin gets no scroll bridge, since its target already has the host's scroll")
    void mixinGetsNoBridge() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/mixin/ScreenMixin", null, SCREEN, null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "mouseScrolled", LegacyGuiOverrideAdapter.OLD_SCROLL, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(LegacyGuiOverrideAdapter.apply(cw.toByteArray())).accept(node, 0);
        assertTrue(node.methods.stream().noneMatch(m -> m.desc.equals(LegacyGuiOverrideAdapter.NEW_SCROLL)));
    }
}
