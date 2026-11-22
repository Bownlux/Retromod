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
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.function.BiPredicate;

import static com.retromod.shim.common.LegacyPlayerWillDestroyAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 block's void playerWillDestroy keeps running on a host whose method returns the block state. */
class LegacyPlayerWillDestroyAdapterTest {

    private static final String BASE_ENTITY_BLOCK = "net/minecraft/world/level/block/BaseEntityBlock";
    private static final BiPredicate<String, String> BLOCKS = (child, ancestor) -> ancestor.equals(BLOCK);

    @Test
    @DisplayName("the old override gets the host's method, and its super call reaches the host's form")
    void oldOverrideIsBridged() throws Exception {
        ClassNode node = node(LegacyPlayerWillDestroyAdapter.apply(oldBlock("mod/GlobeBlock", BASE_ENTITY_BLOCK), BLOCKS));

        MethodNode bridge = method(node, NEW_DESC);
        assertNotNull(bridge, "the host calls the state-returning form, so the block needs one");
        MethodInsnNode runsOld = firstCall(bridge);
        assertEquals(Opcodes.INVOKESPECIAL, runsOld.getOpcode(), "a direct call keeps a subclass from recursing through super");
        assertEquals(OLD_DESC, runsOld.desc);

        MethodInsnNode superCall = firstCall(method(node, OLD_DESC));
        assertEquals(BASE_ENTITY_BLOCK, superCall.owner);
        assertEquals(NEW_DESC, superCall.desc, "the parent's void form is gone from the host");
        assertEquals(Opcodes.POP, superCall.getNext().getOpcode(), "the returned state is dropped where void was expected");
        verify(node);
    }

    @Test
    @DisplayName("a mixin keeps its calls retyped but gets no bridge, since its target already has the host's method")
    void mixinGetsNoBridge() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/mixin/BlockMixin", null, BASE_ENTITY_BLOCK, null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, NAME, OLD_DESC, null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BASE_ENTITY_BLOCK, NAME, OLD_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(LegacyPlayerWillDestroyAdapter.apply(cw.toByteArray(), BLOCKS));

        assertNull(method(node, NEW_DESC), "Mixin merges the class into its target, so no bridge is added");
        assertEquals(NEW_DESC, firstCall(method(node, OLD_DESC)).desc, "the call inside the mixin still reaches the host's form");
    }

    @Test
    @DisplayName("a class that is not a block keeps its own method of the same shape")
    void unrelatedClassIsLeftAlone() {
        byte[] original = oldBlock("mod/NotABlock", "java/lang/Object");
        assertSame(original, LegacyPlayerWillDestroyAdapter.apply(original, (child, ancestor) -> false));
    }

    /** {@code void playerWillDestroy(level, pos, state, player) { super.playerWillDestroy(level, pos, state, player); }}. */
    private static byte[] oldBlock(String name, String parent) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, NAME, OLD_DESC, null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, NAME, OLD_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(ClassNode node, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(NAME) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static MethodInsnNode firstCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call) return call;
        throw new AssertionError("no call in " + method.name + method.desc);
    }

    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
