/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_20_6_to_1_21;
import com.retromod.shim.neoforge.NeoForge_1_20_6_to_1_21;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Regression for #119 (Apollo's Enchantment Rebalance anvil crash): the 1.20.6->1.21 shims must
 * not rewrite {@code Enchantment.getMaxLevel()} / {@code getMinLevel()} to the never-written
 * {@code EnchantmentShim}. Those methods never left the API (1.21 through 26.2), and the redirect
 * target was a phantom class, so the rewritten anvil call died {@code NoClassDefFoundError} the
 * first time a player combined an item + book.
 */
class EnchantmentRedirectRemovedTest {

    private static final String NEO_ENCH = "net/minecraft/world/item/enchantment/Enchantment";
    private static final String YARN_ENCH = "net/minecraft/enchantment/Enchantment";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static byte[] anvilLikeCaller(String enchOwner) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/AnvilLike", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "createResult",
                "(L" + enchOwner + ";)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, enchOwner, "getMaxLevel", "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void assertNoEnchantmentShimCall(byte[] out, String enchOwner) {
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        MethodNode m = cn.methods.stream().filter(x -> x.name.equals("createResult"))
                .findFirst().orElseThrow();
        boolean leftOriginal = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            assertFalse(mi.owner.contains("EnchantmentShim"),
                    "getMaxLevel must not be rewritten to the phantom EnchantmentShim: " + mi.owner);
            if (enchOwner.equals(mi.owner) && "getMaxLevel".equals(mi.name)) leftOriginal = true;
        }
        assertTrue(leftOriginal, "the original getMaxLevel call must be left intact");
    }

    @Test
    @DisplayName("NeoForge 1.20.6->1.21 no longer redirects Enchantment.getMaxLevel")
    void neoforgeShimLeavesGetMaxLevel() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new NeoForge_1_20_6_to_1_21().registerRedirects(t);
        byte[] out = t.transformClass(anvilLikeCaller(NEO_ENCH), "test/AnvilLike.class");
        assertNoEnchantmentShimCall(out, NEO_ENCH);
    }

    @Test
    @DisplayName("Fabric 1.20.6->1.21 no longer redirects Enchantment.getMaxLevel")
    void fabricShimLeavesGetMaxLevel() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new Fabric_1_20_6_to_1_21().registerRedirects(t);
        byte[] out = t.transformClass(anvilLikeCaller(YARN_ENCH), "test/AnvilLike.class");
        assertNoEnchantmentShimCall(out, YARN_ENCH);
    }
}
