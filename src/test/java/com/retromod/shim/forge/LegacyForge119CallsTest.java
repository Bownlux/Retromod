/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** #311: Isle of Berk's spawn packet, menu, despawn check and egg loot on Forge 1.20.1. */
class LegacyForge119CallsTest {

    private static final String MOD = "com/example/Gronckle";
    private static final String RANDOM = "Ljava/util/Random;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        LegacyForge119Calls.register(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void mobSpawnPacketIsBuiltThroughTheMergedPacket() {
        String addMob = "net/minecraft/network/protocol/game/ClientboundAddMobPacket";
        byte[] mob = method("getAddEntityPacket", "()Ljava/lang/Object;", mv -> {
            mv.visitTypeInsn(NEW, addMob);
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, addMob, "<init>",
                    "(Lnet/minecraft/world/entity/LivingEntity;)V", false);
            mv.visitInsn(ARETURN);
        });

        List<AbstractInsnNode> code = code(transformer.transformClass(mob, MOD));
        assertTrue(code.stream().noneMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == NEW),
                "the removed packet class is no longer constructed");
        assertEquals(List.of("LegacyForge119Calls.addMob"), calls(code));
    }

    @Test
    void forgeHookRenamesAndRandomArgumentsLink() {
        String player = "Lnet/minecraft/server/level/ServerPlayer;";
        String provider = "Lnet/minecraft/world/MenuProvider;";
        String uniform = "net/minecraft/util/valueproviders/UniformInt";
        String loot = "net/minecraft/world/level/storage/loot/LootParams$Builder";
        byte[] mob = method("interact", "(" + player + provider + RANDOM + "L" + uniform + ";L" + loot
                + ";Lnet/minecraft/world/entity/Mob;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/network/NetworkHooks", "openGui",
                    "(" + player + provider + ")V", false);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, uniform, "sample", "(" + RANDOM + ")I", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, loot, "withRandom", "(" + RANDOM + ")L" + loot + ";", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 5);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/event/ForgeEventFactory", "canEntityDespawn",
                    "(Lnet/minecraft/world/entity/Mob;)Lnet/minecraftforge/eventbus/api/Event$Result;", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        assertEquals(List.of("NetworkHooks.openScreen", "LegacyForge119Calls.sample",
                        "LegacyForge119Calls.withRandom", "LegacyForge119Calls.canEntityDespawn"),
                calls(code(transformer.transformClass(mob, MOD))));
    }

    @Test
    void generatedHelpersAreValid() {
        new ClassReader(LegacyForge119Calls.generate()).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    private static byte[] method(String name, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<AbstractInsnNode> code(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<AbstractInsnNode> code = new ArrayList<>();
        node.methods.get(0).instructions.forEach(code::add);
        return code;
    }

    /** Calls as simple owner name and method, so embedded owners compare equal. */
    private static List<String> calls(List<AbstractInsnNode> code) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }
}
