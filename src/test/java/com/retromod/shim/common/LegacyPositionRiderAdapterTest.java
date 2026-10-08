/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pre-1.20 rideable mob overrides positionRider(Entity), which 1.20 made final (#311). */
class LegacyPositionRiderAdapterTest {

    private static final String DRAGON = "com/example/DragonBase";
    private static final String MOB = "net/minecraft/world/entity/Mob";
    // Forge 1.20.1 SRG names of the one- and two-argument methods.
    private static final String ONE_ARG = "m_7332_";
    private static final String TWO_ARG = "m_19956_";

    @Test
    void movesTheOverrideOntoTheTwoArgumentMethod() {
        byte[] repaired = LegacyPositionRiderAdapter.apply(dragonOverridingPositionRider(),
                ONE_ARG, TWO_ARG);

        ClassNode node = read(repaired);
        assertNull(method(node, ONE_ARG, LegacyPositionRiderAdapter.ONE_ARG),
                "the final method may no longer be overridden");
        MethodNode moved = method(node, TWO_ARG, LegacyPositionRiderAdapter.TWO_ARG);
        assertNotNull(moved, "the override must now implement positionRider(Entity, MoveFunction)");

        MethodInsnNode superCall = null;
        int storedSlot = -1;
        for (AbstractInsnNode insn : moved.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL) {
                superCall = call;
                assertEquals(Opcodes.ALOAD, call.getPrevious().getOpcode());
                assertEquals(2, ((VarInsnNode) call.getPrevious()).var,
                        "the super call must pass the move function along");
            }
            if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.DSTORE) {
                storedSlot = var.var;
            }
        }
        assertNotNull(superCall);
        assertEquals(MOB + "." + TWO_ARG + LegacyPositionRiderAdapter.TWO_ARG,
                superCall.owner + "." + superCall.name + superCall.desc,
                "calling the final one-argument super method would dispatch back here forever");
        assertEquals(3, storedSlot, "locals after the new parameter shift up one slot");
    }

    @Test
    void leavesClassesWithoutTheOldOverrideAlone() {
        byte[] plain = emptyClass();
        assertSame(plain, LegacyPositionRiderAdapter.apply(plain, ONE_ARG, TWO_ARG));
    }

    /** {@code public void m_7332_(Entity p) { super.m_7332_(p); double y = 1.0; }} */
    private static byte[] dragonOverridingPositionRider() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, DRAGON, null, MOB, null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, ONE_ARG,
                LegacyPositionRiderAdapter.ONE_ARG, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, MOB, ONE_ARG,
                LegacyPositionRiderAdapter.ONE_ARG, false);
        method.visitInsn(Opcodes.DCONST_1);
        method.visitVarInsn(Opcodes.DSTORE, 2);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, DRAGON, null, MOB, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }
}
