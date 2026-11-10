/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Farmer's Delight's wild crops extend {@code FlowerBlock} through vanilla 1.20.1's
 * {@code (MobEffect, int, Properties)} constructor, which 1.20.5 replaced with a holder and seconds.
 */
class LegacyFlowerBlockBridgeTest {

    private static final String CROP = "mod/WildCropBlock";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a vanilla 1.20.1 flower constructor call becomes the host's holder constructor")
    void vanillaShapeReachesTheHolderConstructor() {
        transformer.clearRedirectsForTesting();
        LegacyFlowerBlockBridge.register(transformer, host(LegacyFlowerBlockBridge.HOST_DESC));

        List<String> calls = calls(transformer.transformClass(crop(), CROP + ".class"));

        assertTrue(calls.contains(LegacyFlowerBlockBridge.HELPER + ".stashEffect"), "the effect is stashed: " + calls);
        assertTrue(calls.contains(LegacyFlowerBlockBridge.FLOWER + ".<init>" + LegacyFlowerBlockBridge.HOST_DESC),
                "the host constructor is called: " + calls);
    }

    @Test
    @DisplayName("a host that still has the vanilla constructor leaves the call alone")
    void aHostWithTheOldConstructorIsLeftAlone() {
        transformer.clearRedirectsForTesting();
        LegacyFlowerBlockBridge.register(transformer, host(LegacyFlowerBlockBridge.HOST_DESC,
                LegacyFlowerBlockBridge.VANILLA_DESC, LegacyFlowerBlockBridge.FORGE_DESC));

        List<String> calls = calls(transformer.transformClass(crop(), CROP + ".class"));

        assertEquals(List.of(LegacyFlowerBlockBridge.FLOWER + ".<init>" + LegacyFlowerBlockBridge.VANILLA_DESC), calls);
    }

    @Test
    @DisplayName("offline, a 1.20.5 or newer target adapts the old constructor and an older one does not")
    void offlineFollowsTheTarget() {
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
            transformer.clearRedirectsForTesting();
            LegacyFlowerBlockBridge.register(transformer, null);
            assertTrue(calls(transformer.transformClass(crop(), CROP + ".class"))
                    .contains(LegacyFlowerBlockBridge.HELPER + ".stashEffect"));

            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.20.1";
            transformer.clearRedirectsForTesting();
            LegacyFlowerBlockBridge.register(transformer, null);
            assertEquals(List.of(LegacyFlowerBlockBridge.FLOWER + ".<init>" + LegacyFlowerBlockBridge.VANILLA_DESC),
                    calls(transformer.transformClass(crop(), CROP + ".class")));
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    private static ClassNode host(String... constructors) {
        ClassNode node = new ClassNode();
        node.name = LegacyFlowerBlockBridge.FLOWER;
        for (String desc : constructors) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null));
        return node;
    }

    /** {@code WildCropBlock(MobEffect effect, int seconds, Properties p) { super(effect, seconds, p); }} */
    private static byte[] crop() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CROP, null, LegacyFlowerBlockBridge.FLOWER, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", LegacyFlowerBlockBridge.VANILLA_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyFlowerBlockBridge.FLOWER, "<init>",
                LegacyFlowerBlockBridge.VANILLA_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodNode ctor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        return Arrays.stream(ctor.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .map(c -> c.owner + "." + c.name + (c.name.equals("<init>") ? c.desc : "")).toList();
    }
}
