/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Supplementaries adds its pots with FlowerPotBlock.addPlant during common setup, which NeoForge 26.3 removed. */
class LegacyFlowerPotBridgeTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old addPlant call goes to the rebuilt helper on a NeoForge 26.3 host")
    void addPlantIsRedirected() {
        MethodInsnNode call = addPlantCall("26.3", "net/minecraft/resources/Identifier");

        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyFlowerPotBridge.POTS, call.owner);
        assertEquals(LegacyFlowerPotBridge.HELPER_DESC, call.desc);
    }

    @Test
    @DisplayName("NeoForge 26.2, which still has addPlant, keeps the call")
    void olderHostKeepsTheCall() {
        MethodInsnNode call = addPlantCall("26.2", "net/minecraft/resources/Identifier");

        assertEquals(LegacyFlowerPotBridge.POT, call.owner);
    }

    private static MethodInsnNode addPlantCall(String host, String id) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFlowerPotBridge.register(transformer);
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(registerPots(id), "mod/FlowerPots")).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call");
    }

    /** {@code static void add(FlowerPotBlock pot, Identifier flax, Supplier full) { pot.addPlant(flax, full); }} */
    private static byte[] registerPots(String id) {
        String desc = "(L" + id + ";Ljava/util/function/Supplier;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/FlowerPots", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "add",
                "(L" + LegacyFlowerPotBridge.POT + ";L" + id + ";Ljava/util/function/Supplier;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyFlowerPotBridge.POT, "addPlant", desc, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
