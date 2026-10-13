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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Mythic Fantasies, a 1.19.2 Forge mod, on 26.3: its biome set fog and sky colors on
 * {@code BiomeSpecialEffects.Builder}, which 1.21.11 moved to environment attributes, so the biome
 * failed to build, and {@code waterFogColor} had been guessed into {@code waterColor}.
 */
class LegacyBiomeEffectsBridgeTest {

    private static final String BUILDER = "net/minecraft/world/level/biome/BiomeSpecialEffects$Builder";
    private static final String L_BUILDER = "L" + BUILDER + ";";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("removed effect setters, build, and specialEffects go through the helper; kept setters stay")
    void removedSettersAreBridged() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBiomeEffectsBridge.register(transformer);

        List<String> calls = calls(transformer.transformClass(biome(), "mod/GourdBiome.class"));

        assertEquals(List.of(
                LegacyBiomeEffectsBridge.HELPER + ".fogColor",
                LegacyBiomeEffectsBridge.HELPER + ".waterFogColor",
                BUILDER + ".waterColor",
                LegacyBiomeEffectsBridge.HELPER + ".build",
                LegacyBiomeEffectsBridge.HELPER + ".specialEffects"), calls);
    }

    private static byte[] biome() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/GourdBiome", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create",
                "(" + L_BUILDER + "Lnet/minecraft/world/level/biome/Biome$BiomeBuilder;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        setter(mv, "fogColor", 0xC0D8FF);
        setter(mv, "waterFogColor", 0x050533);
        setter(mv, "waterColor", 0x3F76E4);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "build", "()Lnet/minecraft/world/level/biome/BiomeSpecialEffects;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/biome/Biome$BiomeBuilder", "specialEffects",
                "(Lnet/minecraft/world/level/biome/BiomeSpecialEffects;)Lnet/minecraft/world/level/biome/Biome$BiomeBuilder;",
                false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void setter(MethodVisitor mv, String name, int color) {
        mv.visitLdcInsn(color);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, name, "(I)" + L_BUILDER, false);
    }

    private static List<String> calls(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }
}
