/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** 1.20.1 tools with attack damage and speed constructor arguments, on a 1.21.1 host. */
class LegacyToolItemBridgeTest {

    private static final String TIER = "Lnet/minecraft/world/item/Tier;";
    private static final String PROPS = "Lnet/minecraft/world/item/Item$Properties;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new SwordItem(tier, damage, speed, props) becomes the generated factory")
    void oldSwordConstructionUsesTheFactory() {
        LegacyToolItemBridge.registerRedirects(transformer, LegacyToolItemBridge.candidates());
        String oldCtor = "(" + TIER + "IF" + PROPS + ")V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModItems", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sword", "(" + TIER + PROPS + ")Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, LegacyToolItemBridge.SWORD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_3);
        mv.visitLdcInsn(-2.4f);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, LegacyToolItemBridge.SWORD, "<init>", oldCtor, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<MethodInsnNode> calls = calls(transformer.transformClass(cw.toByteArray(), "test/ModItems"));
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(LegacyToolItemBridge.HELPER)
                        && c.name.equals("newSwordItem")), "the removed constructor must go through the factory");
        assertFalse(calls.stream().anyMatch(c -> c.owner.equals(LegacyToolItemBridge.SWORD)
                        && c.desc.equals(oldCtor)), "the removed constructor must not be called");
    }

    @Test
    @DisplayName("A mod pickaxe's old super call is converted to the 1.20.5 constructor")
    void modPickaxeSuperCallIsConverted() {
        LegacyToolItemBridge.registerRedirects(transformer, LegacyToolItemBridge.candidates());
        String pickaxe = "net/minecraft/world/item/PickaxeItem";
        String oldCtor = "(" + TIER + "IF" + PROPS + ")V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/CommandBlockPickaxe", null, pickaxe, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + TIER + PROPS + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_1);
        mv.visitLdcInsn(-2.8f);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, pickaxe, "<init>", oldCtor, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/CommandBlockPickaxe");
        assertEquals(pickaxe, new ClassReader(out).getSuperName(), "the mod tool keeps its host superclass");
        List<MethodInsnNode> calls = calls(out);
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(LegacyToolItemBridge.HELPER)
                && c.name.equals("stashPickaxeItem") && c.desc.equals(oldCtor)),
                "the 1.20.1 arguments are converted before the super call");
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(pickaxe) && c.name.equals("<init>")
                && c.desc.equals("(" + TIER + PROPS + ")V")), "the super call uses the 1.20.5 constructor");
        assertFalse(calls.stream().anyMatch(c -> c.owner.equals(pickaxe) && c.desc.equals(oldCtor)),
                "the removed constructor must not be called");
    }

    @Test
    @DisplayName("The generated tool helper is well formed and covers every tool")
    void generatedHelperIsWellFormed() {
        LegacyToolItemBridge.registerRedirects(transformer, LegacyToolItemBridge.candidates());
        byte[] bytes = transformer.getSyntheticClasses().get(LegacyToolItemBridge.HELPER);
        assertNotNull(bytes);
        assertDoesNotThrow(() -> new ClassReader(bytes).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
        ClassNode helper = new ClassNode();
        new ClassReader(bytes).accept(helper, 0);
        for (String tool : List.of("SwordItem", "AxeItem", "PickaxeItem", "ShovelItem", "HoeItem", "DiggerItem")) {
            assertTrue(helper.methods.stream().anyMatch(m -> m.name.equals("stash" + tool)), tool);
        }
        for (String tool : List.of("SwordItem", "AxeItem", "PickaxeItem", "ShovelItem", "HoeItem")) {
            assertTrue(helper.methods.stream().anyMatch(m -> m.name.equals("new" + tool)), tool);
        }
    }

    private static List<MethodInsnNode> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }));
        return calls;
    }
}
