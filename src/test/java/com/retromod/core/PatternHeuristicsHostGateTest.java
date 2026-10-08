/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The pattern rules describe 26.1 renames. On a 1.21.1 NeoForge host a migrated Forge mod's
 * {@code player.displayClientMessage(text, true)} was rewritten to the 26.1-only
 * {@code sendSystemMessage(text)} with the boolean left on the stack, a VerifyError (#306).
 */
class PatternHeuristicsHostGateTest {

    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";

    private RetromodTransformer transformer;
    private String savedTarget;

    @BeforeEach
    void setUp() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // Any registration keeps the transformer from taking its nothing-registered fast exit.
        transformer.registerClassRedirect("test/Unrelated", "test/Elsewhere");
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void olderHostGetsNoPatternRules() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertNull(transformer.patternHeuristicsForHost(),
                "1.21.1 still has displayClientMessage, so no 26.1 rename may run there");
        MethodInsnNode call = messageCall(transformer.transformClass(caller(), "test/Caller"));
        assertEquals("displayClientMessage", call.name, "1.21.1 still has displayClientMessage");
        assertEquals("(" + COMPONENT + "Z)V", call.desc);
    }

    @Test
    void unobfuscatedHostKeepsThePatternRules() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertNotNull(transformer.patternHeuristicsForHost(), "26.1 is where the renames apply");
        assertNotNull(transformer.patternHeuristicsForHost().resolveMethod(PLAYER, "displayClientMessage",
                "(" + COMPONENT + "Z)V"), "the displayClientMessage rule still exists for 26.1 hosts");
    }

    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "tell", "(L" + PLAYER + ";" + COMPONENT + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "displayClientMessage", "(" + COMPONENT + "Z)V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode messageCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(PLAYER)) return call;
        }
        throw new AssertionError("no Player call left");
    }
}
