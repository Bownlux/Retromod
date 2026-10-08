/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
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

import static com.retromod.shim.common.LegacyFoodPropertiesBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mod's food item, after the SRG remap, against the 1.20.5 food component. */
class LegacyFoodPropertiesBridgeTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void renamedBuilderCallsReachTheHostMembers() {
        List<String> calls = calls(transform(), "food");

        assertTrue(calls.contains(BUILDER + ".saturationModifier(F)L" + BUILDER + ";"),
                "saturationMod must become saturationModifier: " + calls);
        assertTrue(calls.contains(BUILDER + ".alwaysEdible()L" + BUILDER + ";"),
                "alwaysEat must become alwaysEdible: " + calls);
        assertFalse(calls.stream().anyMatch(c -> c.contains(".saturationMod(") || c.contains(".alwaysEat(")),
                "no removed builder member may survive: " + calls);
    }

    @Test
    void membersWithoutAReplacementGoThroughTheHelper() {
        List<String> calls = calls(transform(), "food");

        assertTrue(calls.contains(HELPER + ".meat(L" + BUILDER + ";)L" + BUILDER + ";"),
                "meat() must keep the builder through the helper: " + calls);
        assertTrue(calls.contains(HELPER + ".getSaturationModifier(L" + FOOD + ";)F"),
                "the old modifier must be read back through the helper: " + calls);
        assertTrue(calls.contains(HELPER + ".getFoodProperties(L" + ITEM + ";)L" + FOOD + ";"),
                "Item.getFoodProperties() must read the food component: " + calls);
    }

    @Test
    void theNutritionGetterBecomesTheRecordAccessor() {
        List<String> calls = calls(transform(), "food");

        assertTrue(calls.contains(FOOD + ".nutrition()I"), "getNutrition must become nutrition: " + calls);
    }

    @Test
    void theGeneratedHelperIsStructurallyValid() throws Exception {
        ClassNode helper = node(generateHelper(candidates()));

        assertEquals(7, helper.methods.size(), "one helper per member without a host replacement");
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void aBuildWithOnlyRenamesRegistersNoHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, candidates().stream().filter(r -> !r.viaHelper()).toList());

        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER),
                "a host that only renamed members needs no generated class");
    }

    private static byte[] transform() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, candidates());
        return transformer.transformClass(legacyFood(), "test/ModFoods.class");
    }

    /** {@code new Builder().nutrition(4).saturationMod(0.3F).meat().alwaysEat().build()} and friends. */
    private static byte[] legacyFood() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModFoods", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "food",
                "(L" + ITEM + ";)F", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, BUILDER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "nutrition", "(I)L" + BUILDER + ";", false);
        mv.visitLdcInsn(0.3f);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "saturationMod", "(F)L" + BUILDER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "meat", "()L" + BUILDER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "alwaysEat", "()L" + BUILDER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", "()L" + FOOD + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD, "getNutrition", "()I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "getFoodProperties", "()L" + FOOD + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD, "getSaturationModifier", "()F", false);
        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        List<String> calls = new ArrayList<>();
        MethodNode method = node(bytes).methods.stream().filter(m -> m.name.equals(methodName))
                .findFirst().orElseThrow();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
