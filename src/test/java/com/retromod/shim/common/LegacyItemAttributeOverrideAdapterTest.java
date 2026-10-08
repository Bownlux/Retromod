/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.common.LegacyItemAttributeOverrideAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.20.1 item that describes its bonuses through the removed
 * {@code getDefaultAttributeModifiers(EquipmentSlot)}, against NeoForge 1.21's stack hook.
 */
class LegacyItemAttributeOverrideAdapterTest {

    @Test
    void theOldOverrideAnswersNeoForgesStackHook() throws Exception {
        ClassNode item = node(apply(legacyItem(false), (child, ancestor) -> true));

        MethodNode hook = method(item, OLD_DEFAULTS, NEW_DESC);
        assertNotNull(hook, "the stack hook must be added");
        List<String> calls = calls(hook);
        assertEquals(LEGACY_SLOTS.length, calls.stream()
                        .filter(call -> call.equals("test/LegacyCharm." + OLD_DEFAULTS + OLD_DEFAULTS_DESC)).count(),
                "the old override must be asked once per pre-1.20.5 slot: " + calls);
        assertTrue(calls.contains(HELPER + ".addAll"), "each answer must be collected: " + calls);
        new Analyzer<>(new BasicVerifier()).analyze(item.name, hook);
    }

    @Test
    void theStackAwareForgeOverrideIsPreferred() {
        ClassNode item = node(apply(legacyItem(true), (child, ancestor) -> true));

        List<String> calls = calls(method(item, OLD_DEFAULTS, NEW_DESC));
        assertTrue(calls.contains("test/LegacyCharm." + OLD_STACK_AWARE + OLD_STACK_AWARE_DESC),
                "the stack-aware override must be asked with the stack: " + calls);
    }

    @Test
    void theOverridesSuperCallReadsTheParentsComponent() {
        ClassNode item = node(apply(legacyItem(false), (child, ancestor) -> true));

        List<String> calls = calls(method(item, OLD_DEFAULTS, OLD_DEFAULTS_DESC));
        assertTrue(calls.contains(HELPER + ".componentModifiers"),
                "super must not dispatch back into the override: " + calls);
        assertFalse(calls.contains(ITEM + "." + OLD_DEFAULTS), "the removed method must not be called");
    }

    @Test
    void aClassThatIsNotAnItemGetsNoHook() {
        ClassNode other = node(apply(legacyItem(false), (child, ancestor) -> false));

        assertNull(method(other, OLD_DEFAULTS, NEW_DESC), "only items have the stack hook");
    }

    @Test
    void anItemWithItsOwnStackHookKeepsIt() {
        byte[] withHook = legacyItemWithNewHook();

        ClassNode item = node(apply(withHook, (child, ancestor) -> true));
        assertEquals(1, item.methods.stream()
                .filter(m -> m.name.equals(OLD_DEFAULTS) && m.desc.equals(NEW_DESC)).count());
    }

    @Test
    void theGeneratedHelperIsStructurallyValid() throws Exception {
        ClassNode helper = node(generateHelper());
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void offlineOnlyANeoForge121TargetGetsTheRepair() {
        assertTrue(offlineNeedsRepair(true, "1.21.1"));
        assertFalse(offlineNeedsRepair(true, "1.20.4"), "the old method is still there");
        assertFalse(offlineNeedsRepair(true, "1.21.4"), "no verified shape for later versions");
        assertFalse(offlineNeedsRepair(false, "1.21.1"), "the stack hook is NeoForge's");
    }

    /** A charm whose old override adds to {@code super}'s answer. */
    private static byte[] legacyItem(boolean stackAware) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyCharm", null, ITEM, null);
        String name = stackAware ? OLD_STACK_AWARE : OLD_DEFAULTS;
        String desc = stackAware ? OLD_STACK_AWARE_DESC : OLD_DEFAULTS_DESC;
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        if (stackAware) mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM, name, desc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] legacyItemWithNewHook() {
        ClassNode node = node(legacyItem(false));
        MethodNode own = new MethodNode(Opcodes.ACC_PUBLIC, OLD_DEFAULTS, NEW_DESC, null, null);
        own.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
        own.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        node.methods.add(own);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner.equals(HELPER) || call.owner.equals(ITEM)
                        ? call.owner + "." + call.name : call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
