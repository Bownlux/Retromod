/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE's philosopher's stone stays in the crafting grid through the old remainder override. */
class LegacyCraftingRemainderBridgeTest {

    @Test
    @DisplayName("An item overriding the old remainder methods gains the instance form, which verifies")
    void overridesGainTheInstanceForm() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Stone", null, "net/minecraft/world/item/Item", null);
        for (String[] method : new String[][]{{LegacyCraftingRemainderBridge.HAS, LegacyCraftingRemainderBridge.HAS_DESC},
                {LegacyCraftingRemainderBridge.GET, LegacyCraftingRemainderBridge.GET_DESC}}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method[0], method[1], null, null);
            mv.visitCode();
            if (method[1].endsWith("Z")) {
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IRETURN);
            } else {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitInsn(ARETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();

        byte[] repaired = LegacyCraftingRemainderBridge.apply(cw.toByteArray());

        ClassNode node = new ClassNode();
        new ClassReader(repaired).accept(node, 0);
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("getCraftingRemainder")
                && m.desc.equals(LegacyCraftingRemainderBridge.NEW_DESC)));
        new ClassReader(repaired).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        new ClassReader(LegacyCraftingRemainderBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }
}
