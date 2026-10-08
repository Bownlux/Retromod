/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge event hooks a 1.20.1 mob mod calls, such as Wither Storm's griefing checks, on NeoForge. */
class LegacyForgeEventFactoryBridgeTest {

    private static final String SPAWN_ARGS = "Lnet/minecraft/world/entity/Mob;"
            + "Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;"
            + "Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;";
    private static final String SPAWN_DATA = "Lnet/minecraft/world/entity/SpawnGroupData;";

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
    @DisplayName("Hooks move to EventHooks, with the two renamed hooks following their new names")
    void hooksMoveToEventHooks() {
        assertTrue(LegacyForgeEventFactoryBridge.register(transformer, Set.of(LegacyForgeEventFactoryBridge.NEO)::contains));

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Storm", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hooks",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;" + SPAWN_ARGS
                        + "Lnet/minecraft/nbt/CompoundTag;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, LegacyForgeEventFactoryBridge.FORGE, "getMobGriefingEvent",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;)Z", false);
        mv.visitInsn(POP);
        for (int slot = 2; slot <= 7; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKESTATIC, LegacyForgeEventFactoryBridge.FORGE, "onFinalizeSpawn",
                "(" + SPAWN_ARGS + "Lnet/minecraft/nbt/CompoundTag;)" + SPAWN_DATA, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, LegacyForgeEventFactoryBridge.FORGE, "onExplosionStart",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;)Z", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Storm")).accept(out, 0);
        List<String> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }));
        String neo = LegacyForgeEventFactoryBridge.NEO;
        assertTrue(calls.contains(neo + ".canEntityGrief(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;)Z"), calls.toString());
        assertTrue(calls.contains(neo + ".finalizeMobSpawn(" + SPAWN_ARGS + ")" + SPAWN_DATA),
                "the NBT argument 1.20.5 removed must be dropped: " + calls);
        assertTrue(calls.contains(neo + ".onExplosionStart(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;)Z"),
                "same-shape hooks keep their name: " + calls);
    }

    @Test
    @DisplayName("A host that still has ForgeEventFactory is left alone")
    void forgeHostIsLeftAlone() {
        assertFalse(LegacyForgeEventFactoryBridge.register(transformer,
                Set.of(LegacyForgeEventFactoryBridge.FORGE, LegacyForgeEventFactoryBridge.NEO)::contains));
        assertTrue(transformer.getClassRedirects().isEmpty());
    }
}
