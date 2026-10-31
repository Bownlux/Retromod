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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE prices potions from {@code server.potionBrewing()}, which 26.3 deleted with the whole class. */
class LegacyPotionBrewingApiTest {

    private static final String OLD = LegacyPotionBrewingApi.OLD;
    private static final String NEW = LegacyPotionBrewingApi.REBUILT;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The server's table, its lists, and a mix's reagent reach the rebuilt class")
    void oldBrewingReadersMoveToTheRebuiltClass() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyPotionBrewingApi.registerClasses(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(brewingMapper(), "mod/BrewingMapper.class")).accept(node, 0);

        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) calls.add(field.owner + "." + field.name);
            }
        }
        assertTrue(calls.contains(INVOKESTATIC + " " + NEW + ".of(Lnet/minecraft/server/MinecraftServer;)L" + NEW + ";"), calls.toString());
        assertTrue(calls.contains(INVOKESTATIC + " " + NEW + ".of(Lnet/minecraft/world/level/Level;)L" + NEW + ";"), calls.toString());
        assertTrue(calls.contains(NEW + ".potionMixes"), calls.toString());
        assertTrue(calls.contains(INVOKEVIRTUAL + " " + NEW + "$Mix.ingredient()Lnet/minecraft/world/item/crafting/Ingredient;"),
                calls.toString());
        assertTrue(calls.stream().noneMatch(call -> call.contains(OLD)), "a call still names the deleted class: " + calls);
    }

    @Test
    @DisplayName("The rebuilt class keeps every public member 26.2's PotionBrewing gave old code")
    void rebuiltClassKeepsTheOldMembers() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyPotionBrewingApi.registerClasses(transformer);
        byte[] rebuilt = transformer.getSyntheticClasses().get(NEW);
        assertNotNull(rebuilt, "the rebuilt PotionBrewing was not registered");
        assertNotNull(transformer.getSyntheticClasses().get(NEW + "$Mix"), "its Mix record was not registered");

        ClassNode node = new ClassNode();
        new ClassReader(rebuilt).accept(node, 0);
        List<String> members = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) != 0) members.add(method.name + method.desc);
        }
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_PUBLIC) != 0) members.add(field.name + ":" + field.desc);
        }
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        for (String expected : List.of("containers:Ljava/util/List;", "potionMixes:Ljava/util/List;",
                "containerMixes:Ljava/util/List;", "EMPTY:L" + NEW + ";", "BREWING_TIME_SECONDS:I",
                "isIngredient(" + stack + ")Z", "isInput(" + stack + ")Z", "getRecipes()Ljava/util/List;",
                "isContainerIngredient(" + stack + ")Z", "isPotionIngredient(" + stack + ")Z",
                "isBrewablePotion(Lnet/minecraft/core/Holder;)Z", "hasMix(" + stack + stack + ")Z",
                "hasContainerMix(" + stack + stack + ")Z", "hasPotionMix(" + stack + stack + ")Z",
                "mix(" + stack + stack + ")" + stack)) {
            assertTrue(members.contains(expected), "the rebuilt class lacks " + expected + ": " + members);
        }
    }

    /** A 1.21.1 EMC mapper: it asks the server and a level for the table and reads a mix's reagent. */
    private static byte[] brewingMapper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/BrewingMapper", null, "java/lang/Object", null);
        MethodVisitor map = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "map",
                "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;)Ljava/lang/Object;", null, null);
        map.visitCode();
        map.visitVarInsn(ALOAD, 1);
        map.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/server/level/ServerLevel", "potionBrewing", "()L" + OLD + ";", false);
        map.visitInsn(POP);
        map.visitVarInsn(ALOAD, 0);
        map.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/server/MinecraftServer", "potionBrewing", "()L" + OLD + ";", false);
        map.visitFieldInsn(GETFIELD, OLD, "potionMixes", "Ljava/util/List;");
        map.visitInsn(ICONST_0);
        map.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        map.visitTypeInsn(CHECKCAST, OLD + "$Mix");
        map.visitMethodInsn(INVOKEVIRTUAL, OLD + "$Mix", "ingredient", "()Lnet/minecraft/world/item/crafting/Ingredient;", false);
        map.visitInsn(ARETURN);
        map.visitMaxs(0, 0);
        map.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
