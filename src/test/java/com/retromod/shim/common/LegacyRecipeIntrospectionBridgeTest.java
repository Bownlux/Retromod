/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE's EMC mappers list and read recipes through the 1.21.1 API, which 1.21.2 rebuilt. */
class LegacyRecipeIntrospectionBridgeTest {

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Recipe lists, ingredients, results, and ingredient stacks reach the forwarders")
    void recipeQueriesAreRedirected() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyRecipeIntrospectionBridge.register(transformer);

        ClassNode mapper = read(transformer.transformClass(mapper(), "mod/Mapper"));

        List<String> calls = new ArrayList<>();
        for (MethodNode method : mapper.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        for (String forwarder : List.of("allRecipesFor", "ingredients", "resultItem", "items")) {
            assertTrue(calls.contains(LegacyRecipeIntrospectionBridge.CALLS + "." + forwarder), forwarder + " in " + calls);
        }
        assertTrue(calls.contains(CRAFTING + "SmeltingRecipe.experience"), calls.toString());
    }

    @Test
    @DisplayName("The generated forwarders verify")
    void generatedClassVerifies() {
        new ClassReader(LegacyRecipeIntrospectionBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    /** One call of each removed recipe question, as ProjectE's mappers make them. */
    private static byte[] mapper() {
        String recipe = "L" + CRAFTING + "Recipe;";
        String smelting = "L" + CRAFTING + "SmeltingRecipe;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Mapper", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "map", "(L" + CRAFTING + "RecipeManager;L" + CRAFTING
                + "RecipeType;" + recipe + smelting + "L" + CRAFTING + "Ingredient;Lnet/minecraft/core/HolderLookup$Provider;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, CRAFTING + "RecipeManager", "getAllRecipesFor", "(L" + CRAFTING + "RecipeType;)Ljava/util/List;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, CRAFTING + "Recipe", "getIngredients", "()Lnet/minecraft/core/NonNullList;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEINTERFACE, CRAFTING + "Recipe", "getResultItem",
                "(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, CRAFTING + "SmeltingRecipe", "getExperience", "()F", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, CRAFTING + "Ingredient", "getItems", "()[Lnet/minecraft/world/item/ItemStack;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
