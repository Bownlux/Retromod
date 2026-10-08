/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.20.5 replaced the {@code SpawnPlacements.Type} enum with the {@code SpawnPlacementType}
 * interface. An old mob registering spawn rules reads {@code SpawnPlacements.Type.ON_GROUND}, and an
 * old spawn predicate calls the removed static {@code NaturalSpawner.isSpawnPositionOk}.
 */
class LegacySpawnPlacementTypeBridgeTest {

    private static final String SPAWNER = "net/minecraft/world/level/NaturalSpawner";
    private static final String OLD_CHECK = "(L" + LegacySpawnPlacementTypeBridge.OLD_TYPE
            + ";Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/EntityType;)Z";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void readsTheConstantFromItsNewHolderAndCallsTheInterfaceThroughTheHelper() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacySpawnPlacementTypeBridge.register(transformer);

        MethodNode rules = method(transformer.transformClass(spawnRules(), "com/example/SpawnRules"));

        FieldInsnNode constant = first(rules, FieldInsnNode.class);
        assertEquals(LegacySpawnPlacementTypeBridge.CONSTANTS, constant.owner,
                "ON_GROUND lives on SpawnPlacementTypes from 1.20.5 on");
        assertEquals("L" + LegacySpawnPlacementTypeBridge.NEW_TYPE + ";", constant.desc);
        MethodInsnNode check = first(rules, MethodInsnNode.class);
        assertEquals(LegacySpawnPlacementTypeBridge.HELPER, check.owner,
                "the removed static check must go through the generated forwarder");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacySpawnPlacementTypeBridge.HELPER),
                "the forwarder must be registered so the embedder ships it");
    }

    @Test
    void registersNothingBefore1205() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacySpawnPlacementTypeBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(LegacySpawnPlacementTypeBridge.OLD_TYPE),
                "the enum still exists before 1.20.5");
    }

    @Test
    void helperForwardsToTheInterfaceMethod() {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacySpawnPlacementTypeBridge.generateHelper()).accept(helper, 0);
        MethodInsnNode call = first(helper.methods.get(0), MethodInsnNode.class);
        assertEquals(Opcodes.INVOKEINTERFACE, call.getOpcode());
        assertEquals(LegacySpawnPlacementTypeBridge.NEW_TYPE, call.owner);
        assertEquals("isSpawnPositionOk", call.name);
    }

    /** {@code boolean check(level, pos, type) { return NaturalSpawner.isSpawnPositionOk(Type.ON_GROUND, ...); }} */
    private static byte[] spawnRules() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/SpawnRules", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "check",
                "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/entity/EntityType;)Z", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, LegacySpawnPlacementTypeBridge.OLD_TYPE, "ON_GROUND",
                "L" + LegacySpawnPlacementTypeBridge.OLD_TYPE + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPAWNER, "isSpawnPositionOk", OLD_CHECK, false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("check")).findFirst().orElseThrow();
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }
}
