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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 block's getCloneItemStack, which asked a block getter, keeps running and its super call links. */
class LegacyCloneItemStack1201Test {

    private static final String BLOCK = "net/minecraft/world/level/block/Block";

    @Test
    @DisplayName("the 1.20.1 override gets the host's form and its super call passes a level reader")
    void oldOverrideIsBridged() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/PotBlock", null, BLOCK, null);
        MethodVisitor clone = cw.visitMethod(Opcodes.ACC_PUBLIC, "getCloneItemStack", LegacyBlockOverridesAdapter.OLD_CLONE_1201, null, null);
        clone.visitCode();
        for (int slot = 0; slot <= 3; slot++) clone.visitVarInsn(Opcodes.ALOAD, slot);
        clone.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "getCloneItemStack", LegacyBlockOverridesAdapter.OLD_CLONE_1201, false);
        clone.visitInsn(Opcodes.ARETURN);
        clone.visitMaxs(0, 0);
        clone.visitEnd();
        cw.visitEnd();

        byte[] out = LegacyBlockOverridesAdapter.apply(cw.toByteArray(), Set.of(LegacyBlockOverridesAdapter.CLONE_STACK));
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);

        MethodNode bridge = node.methods.stream().filter(m -> m.desc.equals(LegacyBlockOverridesAdapter.NEW_CLONE)).findFirst().orElse(null);
        assertNotNull(bridge, "the host asks the four-argument form");
        MethodNode old = node.methods.stream().filter(m -> m.desc.equals(LegacyBlockOverridesAdapter.OLD_CLONE_1201)).findFirst().orElseThrow();
        MethodInsnNode superCall = null;
        for (var insn : old.instructions) if (insn instanceof MethodInsnNode call) superCall = call;
        assertEquals(LegacyBlockOverridesAdapter.NEW_CLONE, superCall.desc, "the super call reaches the host's form");
        for (MethodNode method : node.methods) {
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter()).analyze(node.name, method);
        }
    }
}
