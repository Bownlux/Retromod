/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** 26.3 replaced ContextAwarePredicate, which every custom advancement trigger from 1.20.2 to 26.2 reads its player with. */
class LegacyContextAwarePredicateBridgeTest {

    private static final String OLD = LegacyContextAwarePredicateBridge.OLD;
    private static final String HOLDER = LegacyContextAwarePredicateBridge.HOLDER;
    private static final String HELPER = LegacyContextAwarePredicateBridge.HELPER;
    private static final String CONDITION = LegacyContextAwarePredicateBridge.CONDITION;
    private static final String LOOT_CONTEXT = "net/minecraft/world/level/storage/loot/LootContext";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A trigger's predicate type, codec, matches and create reach the holder and the helper")
    void oldTriggerUsesTheHolder() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyContextAwarePredicateBridge.registerBridge(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(trigger(), "mod/DragonTrigger.class")).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            refs.add("desc " + method.name + method.desc);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
            }
        }
        assertTrue(refs.contains("desc test(L" + HOLDER + ";L" + LOOT_CONTEXT + ";)Z"), refs.toString());
        assertTrue(refs.contains(INVOKESTATIC + " " + HELPER + ".matches(L" + HOLDER + ";L" + LOOT_CONTEXT + ";)Z"), refs.toString());
        assertTrue(refs.contains(INVOKESTATIC + " " + HELPER + ".create([L" + CONDITION + ";)L" + HOLDER + ";"), refs.toString());
        assertTrue(refs.contains(CONDITION + ".CODEC"), "the player codec reads a loot condition holder: " + refs);
        assertTrue(refs.stream().noneMatch(ref -> ref.contains(OLD)), "the removed type is still named: " + refs);
    }

    @Test
    @DisplayName("The generated helper verifies")
    void helperVerifies() {
        new ClassReader(LegacyContextAwarePredicateBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    /** A 26.2 trigger: it reads its player with ADVANCEMENT_CODEC, tests it, and builds an empty predicate. */
    private static byte[] trigger() {
        String predicate = "L" + OLD + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/DragonTrigger", null, "java/lang/Object", null);
        MethodVisitor test = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "test", "(" + predicate + "L" + LOOT_CONTEXT + ";)Z", null, null);
        test.visitCode();
        test.visitFieldInsn(GETSTATIC, "net/minecraft/advancements/predicates/entity/EntityPredicate", "ADVANCEMENT_CODEC",
                "Lcom/mojang/serialization/Codec;");
        test.visitInsn(POP);
        test.visitInsn(ICONST_0);
        test.visitTypeInsn(ANEWARRAY, CONDITION);
        test.visitMethodInsn(INVOKESTATIC, OLD, "create", "([L" + CONDITION + ";)" + predicate, false);
        test.visitInsn(POP);
        test.visitVarInsn(ALOAD, 0);
        test.visitVarInsn(ALOAD, 1);
        test.visitMethodInsn(INVOKEVIRTUAL, OLD, "matches", "(L" + LOOT_CONTEXT + ";)Z", false);
        test.visitInsn(IRETURN);
        test.visitMaxs(0, 0);
        test.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
