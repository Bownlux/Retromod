/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Forge 66 (Minecraft 26.3) renamed a handful of API members without changing what they do.
 * Checked on both sides against the 65.1.3 and 66.0.5 universal jars.
 */
class Forge2603RenamesTest {

    private static final String BIOME_BUILDER =
            "net/minecraftforge/common/world/ModifiableBiomeInfo$BiomeInfo$Builder";
    private static final String OVERLAY =
            "net/minecraftforge/client/event/RenderBlockScreenEffectEvent";
    private static final String TELEPORT = "net/minecraftforge/event/entity/EntityTeleportEvent$";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        McReflect.setForceNeoForge(false);
    }

    @Test
    @DisplayName("a Forge 26.2 mod's renamed calls reach their Forge 66 names")
    void renamesApplyOnForge() {
        new Forge_26_2_to_26_3().registerRedirects(transformer);
        List<String> calls = calls(transformer.transformClass(caller(), "test/mod/Listener"));

        assertTrue(calls.contains(BIOME_BUILDER + ".climateSettings"), calls.toString());
        assertTrue(calls.contains(BIOME_BUILDER + ".effects"), calls.toString());
        assertTrue(calls.contains(BIOME_BUILDER + ".generationSettings"), calls.toString());
        assertTrue(calls.contains("type " + TELEPORT + "EntityRandom"), calls.toString());
    }

    @Test
    @DisplayName("on NeoForge the Forge names are left to the Forge to NeoForge migration")
    void leftAloneOnNeoForge() {
        McReflect.setForceNeoForge(true);
        new Forge_26_2_to_26_3().registerRedirects(transformer);
        List<String> calls = calls(transformer.transformClass(caller(), "test/mod/Listener"));

        assertTrue(calls.contains(BIOME_BUILDER + ".getClimateSettings"), calls.toString());
    }

    /** Calls each renamed member once and checks the teleport event's type. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Listener", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "handle",
                "(L" + BIOME_BUILDER + ";L" + OVERLAY + ";Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BIOME_BUILDER, "getClimateSettings",
                "()Lnet/minecraftforge/common/world/ClimateSettingsBuilder;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BIOME_BUILDER, "getGenerationSettings",
                "()Lnet/minecraft/world/level/biome/BiomeGenerationSettings$PlainBuilder;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BIOME_BUILDER, "getSpecialEffects",
                "()Lnet/minecraftforge/common/world/BiomeSpecialEffectsBuilder;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, OVERLAY, "getBlockState",
                "()Lnet/minecraft/world/level/block/state/BlockState;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(INSTANCEOF, TELEPORT + "EnderEntity");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
                if (insn instanceof TypeInsnNode type) out.add("type " + type.desc);
            }
        }
        return out;
    }
}
