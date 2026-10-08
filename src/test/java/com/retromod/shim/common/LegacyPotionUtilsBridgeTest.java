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

/**
 * A 1.20.1 mod tagging a brewing output with {@code PotionUtils.setPotion}, as Wither Storm does,
 * on a 1.20.5+ host where potions live in the {@code potion_contents} component.
 */
class LegacyPotionUtilsBridgeTest {

    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String POTION = "Lnet/minecraft/world/item/alchemy/Potion;";

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
    @DisplayName("setPotion keeps its call shape and lands on the generated component helper")
    void setPotionCallIsRedirected() {
        LegacyPotionUtilsBridge.registerRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Brewing", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "output", "(" + STACK + POTION + ")" + STACK, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, LegacyPotionUtilsBridge.POTION_UTILS, "setPotion",
                "(" + STACK + POTION + ")" + STACK, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Brewing")).accept(out, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }));
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(LegacyPotionUtilsBridge.GENERATED)
                        && c.name.equals("setPotion") && c.desc.equals("(" + STACK + POTION + ")" + STACK)),
                "PotionUtils is gone, so the call must reach the generated class with the same shape");
    }

    @Test
    @DisplayName("The generated helpers write the potion_contents component")
    void generatedHelpersUseTheComponent() {
        byte[] generated = LegacyPotionUtilsBridge.generate();
        assertDoesNotThrow(() -> new ClassReader(generated).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
        ClassNode node = new ClassNode();
        new ClassReader(generated).accept(node, 0);
        for (String[] method : new String[][] {
                {"setPotion", "(" + STACK + POTION + ")" + STACK},
                {"setCustomEffects", "(" + STACK + "Ljava/util/Collection;)" + STACK},
                {"getPotion", "(" + STACK + ")" + POTION}}) {
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(method[0]) && m.desc.equals(method[1])),
                    "the old static " + method[0] + " must exist with its 1.20.1 descriptor");
        }
        List<String> called = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) called.add(call.owner + "." + call.name);
        }));
        assertTrue(called.contains("net/minecraft/world/item/ItemStack.set"), "the potion is stored as a component");
        assertTrue(called.contains(LegacyPotionUtilsBridge.CONTENTS + ".withPotion"));
        assertTrue(called.contains(LegacyPotionUtilsBridge.CONTENTS + ".withEffectAdded"));
    }
}
