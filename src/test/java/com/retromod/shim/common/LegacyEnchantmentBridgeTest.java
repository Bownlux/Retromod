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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Fossils and Archeology (1.20.1) and Spartan Shields (1.12.2) define enchantments by extending
 * {@code Enchantment}, which 1.21 made a final record read from data, so their classes failed to load.
 */
class LegacyEnchantmentBridgeTest {

    private static final String OLD = "net/minecraft/world/item/enchantment/Enchantment";
    private static final String RARITY = "Lnet/minecraft/world/item/enchantment/Enchantment$Rarity;";
    private static final String CATEGORY = "Lnet/minecraft/world/item/enchantment/EnchantmentCategory;";
    private static final String SLOTS = "[Lnet/minecraft/world/entity/EquipmentSlot;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old enchantment class extends the generated class with the old constructor and lookups")
    void oldEnchantmentIsRebased() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyEnchantmentBridge.registerRedirects(transformer);

        byte[] renamed = LegacyEnchantmentBridge.rename(archeology());
        ClassNode out = read(transformer.transformClass(renamed, "mod/ArcheologyEnchantment.class"));

        assertEquals(LegacyEnchantmentBridge.LEGACY, out.superName);
        List<String> calls = calls(out);
        assertTrue(calls.contains(LegacyEnchantmentBridge.LEGACY + ".<init>" + LegacyEnchantmentBridge.CONSTRUCTOR),
                "super(rarity, category, slots) reaches the generated constructor, saw " + calls);
        assertTrue(calls.contains(LegacyEnchantmentBridge.SUPPORT + ".level(Ljava/lang/Object;Ljava/lang/Object;)I"),
                "a level lookup matches the item's enchantments by id, saw " + calls);
    }

    @Test
    @DisplayName("a jar is renamed only when it was written against the old class")
    void onlyOldJarsAreRenamed() {
        assertTrue(LegacyEnchantmentBridge.usesOldEnchantment(archeology()));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "mod/Plain", null, "java/lang/Object", null);
        cw.visitEnd();
        assertFalse(LegacyEnchantmentBridge.usesOldEnchantment(cw.toByteArray()));
    }

    @Test
    @DisplayName("the generated enchantment and holder verify")
    void generatedClassesVerify() throws Exception {
        for (byte[] generated : List.of(LegacyEnchantmentBridge.generateEnchantment(), LegacyEnchantmentBridge.generateHolder())) {
            ClassNode node = read(generated);
            for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
        assertEquals("net/neoforged/neoforge/registries/DeferredHolder",
                read(LegacyEnchantmentBridge.generateHolder()).superName,
                "the mod keeps its holder field type, so the holder must be one");
    }

    /** {@code ArcheologyEnchantment}: super(VERY_RARE, DIGGER, MAINHAND), and a level read in a helper. */
    private static byte[] archeology() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/ArcheologyEnchantment", null, OLD, null);
        MethodVisitor init = cw.visitMethod(ACC_PROTECTED, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitFieldInsn(GETSTATIC, OLD + "$Rarity", "VERY_RARE", RARITY);
        init.visitFieldInsn(GETSTATIC, "net/minecraft/world/item/enchantment/EnchantmentCategory", "DIGGER", CATEGORY);
        init.visitInsn(ICONST_0);
        init.visitTypeInsn(ANEWARRAY, "net/minecraft/world/entity/EquipmentSlot");
        init.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", "(" + RARITY + CATEGORY + SLOTS + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor level = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "level",
                "(L" + OLD + ";Lnet/minecraft/world/item/ItemStack;)I", null, null);
        level.visitCode();
        level.visitVarInsn(ALOAD, 0);
        level.visitVarInsn(ALOAD, 1);
        level.visitMethodInsn(INVOKESTATIC, "net/minecraft/world/item/enchantment/EnchantmentHelper",
                "getItemEnchantmentLevel", "(L" + OLD + ";Lnet/minecraft/world/item/ItemStack;)I", false);
        level.visitInsn(IRETURN);
        level.visitMaxs(0, 0);
        level.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
