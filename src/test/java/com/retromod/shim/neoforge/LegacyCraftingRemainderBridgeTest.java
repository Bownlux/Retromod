/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.shim.common.LegacyEarlyItemStackBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    @DisplayName("On a host that binds item components late, a Forge stack's remainder is built to wait for them")
    void forgeRemainderWaitsForComponents() {
        String early = LegacyEarlyItemStackBridge.HELPER + ".of";
        assertTrue(calls(LegacyCraftingRemainderBridge.generateStackCalls(true), "remainder").contains(early),
                "a cooking recipe reads its result's remainder while recipes load, before 26.x binds components");
        assertFalse(calls(LegacyCraftingRemainderBridge.generateStackCalls(false), "remainder").contains(early));
        for (boolean earlyStacks : new boolean[]{false, true}) {
            new ClassReader(LegacyCraftingRemainderBridge.generateStackCalls(earlyStacks))
                    .accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    /** Each method call in {@code name} of the class, as {@code owner.name}. */
    private static List<String> calls(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }
}
